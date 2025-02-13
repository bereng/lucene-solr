/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.document;

import org.apache.lucene.geo.GeoUtils;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FieldDoc;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SortField;
import org.apache.lucene.geo.Polygon;

/** 
 * An indexed location field.
 * <p>
 * Finding all documents within a range at search time is
 * efficient.  Multiple values for the same field in one document
 * is allowed. 
 * <p>
 * This field defines static factory methods for common operations:
 * <ul>
 *   <li>{@link #newBoxQuery newBoxQuery()} for matching points within a bounding box.
 *   <li>{@link #newDistanceQuery newDistanceQuery()} for matching points within a specified distance.
 *   <li>{@link #newDistanceSort newDistanceSort()} for ordering documents by distance from a specified location. 
 *   <li>{@link #newPolygonQuery newPolygonQuery()} for matching points within an arbitrary polygon.
 * </ul>
 * <p>
 * <b>WARNING</b>: Values are indexed with some loss of precision, incurring up to 1E-7 error from the
 * original {@code double} values. 
 * @see PointValues
 */
// TODO ^^^ that is very sandy and hurts the API, usage, and tests tremendously, because what the user passes
// to the field is not actually what gets indexed. Float would be 1E-5 error vs 1E-7, but it might be
// a better tradeoff? then it would be completely transparent to the user and lucene would be "lossless".
public class LatLonPoint extends Field {
  private long currentValue;

  /**
   * Type for an indexed LatLonPoint
   * <p>
   * Each point stores two dimensions with 4 bytes per dimension.
   */
  public static final FieldType TYPE = new FieldType();
  static {
    TYPE.setDimensions(2, Integer.BYTES);
    TYPE.setDocValuesType(DocValuesType.SORTED_NUMERIC);
    TYPE.freeze();
  }
  
  /**
   * Change the values of this field
   * @param latitude latitude value: must be within standard +/-90 coordinate bounds.
   * @param longitude longitude value: must be within standard +/-180 coordinate bounds.
   * @throws IllegalArgumentException if latitude or longitude are out of bounds
   */
  public void setLocationValue(double latitude, double longitude) {
    byte[] bytes = new byte[8];
    int latitudeEncoded = encodeLatitude(latitude);
    int longitudeEncoded = encodeLongitude(longitude);
    NumericUtils.intToSortableBytes(latitudeEncoded, bytes, 0);
    NumericUtils.intToSortableBytes(longitudeEncoded, bytes, Integer.BYTES);
    fieldsData = new BytesRef(bytes);
    currentValue = (((long)latitudeEncoded) << 32) | (longitudeEncoded & 0xFFFFFFFFL);
  }

  /** 
   * Creates a new LatLonPoint with the specified latitude and longitude
   * @param name field name
   * @param latitude latitude value: must be within standard +/-90 coordinate bounds.
   * @param longitude longitude value: must be within standard +/-180 coordinate bounds.
   * @throws IllegalArgumentException if the field name is null or latitude or longitude are out of bounds
   */
  public LatLonPoint(String name, double latitude, double longitude) {
    super(name, TYPE);
    setLocationValue(latitude, longitude);
  }

  private static final int BITS = 32;
  private static final double LONGITUDE_MUL = (0x1L<<BITS)/360.0D;
  private static final double LONGITUDE_DECODE = 1/LONGITUDE_MUL;
  private static final double LATITUDE_MUL  = (0x1L<<BITS)/180.0D;
  private static final double LATITUDE_DECODE  = 1/LATITUDE_MUL;
  
  @Override
  public String toString() {
    StringBuilder result = new StringBuilder();
    result.append(getClass().getSimpleName());
    result.append(" <");
    result.append(name);
    result.append(':');

    result.append(decodeLatitude((int)(currentValue >> 32)));
    result.append(',');
    result.append(decodeLongitude((int)(currentValue & 0xFFFFFFFF)));

    result.append('>');
    return result.toString();
  }

  /**
   * Returns a 64-bit long, where the upper 32 bits are the encoded latitude,
   * and the lower 32 bits are the encoded longitude.
   * @see #decodeLatitude(int)
   * @see #decodeLongitude(int)
   */
  @Override
  public Number numericValue() {
    return currentValue;
  }

  // public helper methods (e.g. for queries)

  /** 
   * Quantizes double (64 bit) latitude into 32 bits (rounding down: in the direction of -90)
   * @param latitude latitude value: must be within standard +/-90 coordinate bounds.
   * @return encoded value as a 32-bit {@code int}
   * @throws IllegalArgumentException if latitude is out of bounds
   */
  public static int encodeLatitude(double latitude) {
    GeoUtils.checkLatitude(latitude);
    // the maximum possible value cannot be encoded without overflow
    if (latitude == 90.0D) {
      latitude = Math.nextDown(latitude);
    }
    return (int) Math.floor(latitude / LATITUDE_DECODE);
  }
  
  /** 
   * Quantizes double (64 bit) latitude into 32 bits (rounding up: in the direction of +90)
   * @param latitude latitude value: must be within standard +/-90 coordinate bounds.
   * @return encoded value as a 32-bit {@code int}
   * @throws IllegalArgumentException if latitude is out of bounds
   */
  public static int encodeLatitudeCeil(double latitude) {
    GeoUtils.checkLatitude(latitude);
    // the maximum possible value cannot be encoded without overflow
    if (latitude == 90.0D) {
      latitude = Math.nextDown(latitude);
    }
    return (int) Math.ceil(latitude / LATITUDE_DECODE);
  }

  /** 
   * Quantizes double (64 bit) longitude into 32 bits (rounding down: in the direction of -180)
   * @param longitude longitude value: must be within standard +/-180 coordinate bounds.
   * @return encoded value as a 32-bit {@code int}
   * @throws IllegalArgumentException if longitude is out of bounds
   */
  public static int encodeLongitude(double longitude) {
    GeoUtils.checkLongitude(longitude);
    // the maximum possible value cannot be encoded without overflow
    if (longitude == 180.0D) {
      longitude = Math.nextDown(longitude);
    }
    return (int) Math.floor(longitude / LONGITUDE_DECODE);
  }
  
  /** 
   * Quantizes double (64 bit) longitude into 32 bits (rounding up: in the direction of +180)
   * @param longitude longitude value: must be within standard +/-180 coordinate bounds.
   * @return encoded value as a 32-bit {@code int}
   * @throws IllegalArgumentException if longitude is out of bounds
   */
  public static int encodeLongitudeCeil(double longitude) {
    GeoUtils.checkLongitude(longitude);
    // the maximum possible value cannot be encoded without overflow
    if (longitude == 180.0D) {
      longitude = Math.nextDown(longitude);
    }
    return (int) Math.ceil(longitude / LONGITUDE_DECODE);
  }

  /** 
   * Turns quantized value from {@link #encodeLatitude} back into a double. 
   * @param encoded encoded value: 32-bit quantized value.
   * @return decoded latitude value.
   */
  public static double decodeLatitude(int encoded) {
    double result = encoded * LATITUDE_DECODE;
    assert result >= GeoUtils.MIN_LAT_INCL && result <= GeoUtils.MAX_LAT_INCL;
    return result;
  }
  
  /** 
   * Turns quantized value from byte array back into a double. 
   * @param src byte array containing 4 bytes to decode at {@code offset}
   * @param offset offset into {@code src} to decode from.
   * @return decoded latitude value.
   */
  public static double decodeLatitude(byte[] src, int offset) {
    return decodeLatitude(NumericUtils.sortableBytesToInt(src, offset));
  }

  /** 
   * Turns quantized value from {@link #encodeLongitude} back into a double. 
   * @param encoded encoded value: 32-bit quantized value.
   * @return decoded longitude value.
   */  
  public static double decodeLongitude(int encoded) {
    double result = encoded * LONGITUDE_DECODE;
    assert result >= GeoUtils.MIN_LON_INCL && result <= GeoUtils.MAX_LON_INCL;
    return result;
  }

  /** 
   * Turns quantized value from byte array back into a double. 
   * @param src byte array containing 4 bytes to decode at {@code offset}
   * @param offset offset into {@code src} to decode from.
   * @return decoded longitude value.
   */
  public static double decodeLongitude(byte[] src, int offset) {
    return decodeLongitude(NumericUtils.sortableBytesToInt(src, offset));
  }
  
  /** sugar encodes a single point as a byte array */
  private static byte[] encode(double latitude, double longitude) {
    byte[] bytes = new byte[2 * Integer.BYTES];
    NumericUtils.intToSortableBytes(encodeLatitude(latitude), bytes, 0);
    NumericUtils.intToSortableBytes(encodeLongitude(longitude), bytes, Integer.BYTES);
    return bytes;
  }
  
  /** sugar encodes a single point as a byte array, rounding values up */
  private static byte[] encodeCeil(double latitude, double longitude) {
    byte[] bytes = new byte[2 * Integer.BYTES];
    NumericUtils.intToSortableBytes(encodeLatitudeCeil(latitude), bytes, 0);
    NumericUtils.intToSortableBytes(encodeLongitudeCeil(longitude), bytes, Integer.BYTES);
    return bytes;
  }

  /** helper: checks a fieldinfo and throws exception if its definitely not a LatLonPoint */
  static void checkCompatible(FieldInfo fieldInfo) {
    // point/dv properties could be "unset", if you e.g. used only StoredField with this same name in the segment.
    if (fieldInfo.getPointDimensionCount() != 0 && fieldInfo.getPointDimensionCount() != TYPE.pointDimensionCount()) {
      throw new IllegalArgumentException("field=\"" + fieldInfo.name + "\" was indexed with numDims=" + fieldInfo.getPointDimensionCount() + 
                                         " but this point type has numDims=" + TYPE.pointDimensionCount() + 
                                         ", is the field really a LatLonPoint?");
    }
    if (fieldInfo.getPointNumBytes() != 0 && fieldInfo.getPointNumBytes() != TYPE.pointNumBytes()) {
      throw new IllegalArgumentException("field=\"" + fieldInfo.name + "\" was indexed with bytesPerDim=" + fieldInfo.getPointNumBytes() + 
                                         " but this point type has bytesPerDim=" + TYPE.pointNumBytes() + 
                                         ", is the field really a LatLonPoint?");
    }
    if (fieldInfo.getDocValuesType() != DocValuesType.NONE && fieldInfo.getDocValuesType() != TYPE.docValuesType()) {
      throw new IllegalArgumentException("field=\"" + fieldInfo.name + "\" was indexed with docValuesType=" + fieldInfo.getDocValuesType() + 
                                         " but this point type has docValuesType=" + TYPE.docValuesType() + 
                                         ", is the field really a LatLonPoint?");
    }
  }

  // static methods for generating queries

  /**
   * Create a query for matching a bounding box.
   * <p>
   * The box may cross over the dateline.
   * @param field field name. must not be null.
   * @param minLatitude latitude lower bound: must be within standard +/-90 coordinate bounds.
   * @param maxLatitude latitude upper bound: must be within standard +/-90 coordinate bounds.
   * @param minLongitude longitude lower bound: must be within standard +/-180 coordinate bounds.
   * @param maxLongitude longitude upper bound: must be within standard +/-180 coordinate bounds.
   * @return query matching points within this box
   * @throws IllegalArgumentException if {@code field} is null, or the box has invalid coordinates.
   */
  public static Query newBoxQuery(String field, double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {
    byte[] lower = encodeCeil(minLatitude, minLongitude);
    byte[] upper = encode(maxLatitude, maxLongitude);
    // Crosses date line: we just rewrite into OR of two bboxes, with longitude as an open range:
    if (maxLongitude < minLongitude) {
      // Disable coord here because a multi-valued doc could match both rects and get unfairly boosted:
      BooleanQuery.Builder q = new BooleanQuery.Builder();
      q.setDisableCoord(true);

      // E.g.: maxLon = -179, minLon = 179
      byte[] leftOpen = lower.clone();
      // leave longitude open
      NumericUtils.intToSortableBytes(Integer.MIN_VALUE, leftOpen, Integer.BYTES);
      Query left = newBoxInternal(field, leftOpen, upper);
      q.add(new BooleanClause(left, BooleanClause.Occur.SHOULD));

      byte[] rightOpen = upper.clone();
      // leave longitude open
      NumericUtils.intToSortableBytes(Integer.MAX_VALUE, rightOpen, Integer.BYTES);
      Query right = newBoxInternal(field, lower, rightOpen);
      q.add(new BooleanClause(right, BooleanClause.Occur.SHOULD));
      return new ConstantScoreQuery(q.build());
    } else {
      return newBoxInternal(field, lower, upper);
    }
  }
  
  private static Query newBoxInternal(String field, byte[] min, byte[] max) {
    return new PointRangeQuery(field, min, max, 2) {
      @Override
      protected String toString(int dimension, byte[] value) {
        if (dimension == 0) {
          return Double.toString(decodeLatitude(value, 0));
        } else if (dimension == 1) {
          return Double.toString(decodeLongitude(value, 0));
        } else {
          throw new AssertionError();
        }
      }
    };
  }
  
  /**
   * Create a query for matching points within the specified distance of the supplied location.
   * @param field field name. must not be null.
   * @param latitude latitude at the center: must be within standard +/-90 coordinate bounds.
   * @param longitude longitude at the center: must be within standard +/-180 coordinate bounds.
   * @param radiusMeters maximum distance from the center in meters: must be non-negative and finite.
   * @return query matching points within this distance
   * @throws IllegalArgumentException if {@code field} is null, location has invalid coordinates, or radius is invalid.
   */
  public static Query newDistanceQuery(String field, double latitude, double longitude, double radiusMeters) {
    return new LatLonPointDistanceQuery(field, latitude, longitude, radiusMeters);
  }
  
  /** 
   * Create a query for matching a polygon.
   * <p>
   * The supplied {@code polygon} must be clockwise or counter-clockwise.
   * @param field field name. must not be null.
   * @param polygons array of polygons. must not be null or empty
   * @return query matching points within this polygon
   * @throws IllegalArgumentException if {@code field} is null, {@code polygons} is null or empty
   */
  public static Query newPolygonQuery(String field, Polygon... polygons) {
    return new LatLonPointInPolygonQuery(field, polygons);
  }

  /**
   * Creates a SortField for sorting by distance from a location.
   * <p>
   * This sort orders documents by ascending distance from the location. The value returned in {@link FieldDoc} for
   * the hits contains a Double instance with the distance in meters.
   * <p>
   * If a document is missing the field, then by default it is treated as having {@link Double#POSITIVE_INFINITY} distance
   * (missing values sort last).
   * <p>
   * If a document contains multiple values for the field, the <i>closest</i> distance to the location is used.
   * 
   * @param field field name. must not be null.
   * @param latitude latitude at the center: must be within standard +/-90 coordinate bounds.
   * @param longitude longitude at the center: must be within standard +/-180 coordinate bounds.
   * @return SortField ordering documents by distance
   * @throws IllegalArgumentException if {@code field} is null or location has invalid coordinates.
   */
  public static SortField newDistanceSort(String field, double latitude, double longitude) {
    return new LatLonPointSortField(field, latitude, longitude);
  }
}
