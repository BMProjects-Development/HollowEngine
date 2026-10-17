package ru.hollowhorizon.hollowengine.client.utils.math;

import java.util.logging.Logger;

/**
 * A vector composed of 3 single-precision components, used to represent
 * locations, offsets, and directions in 3-dimensional space.
 *
 * <p>Methods with names ending in "Local" modify the current instance. They are
 * used to cut down on the creation of new instances.
 *
 * @author Mark Powell
 * @author Joshua Slack
 */
public final class Vector3f implements Cloneable, java.io.Serializable {

    static final long serialVersionUID = 1;
    private static final Logger logger = Logger.getLogger(Vector3f.class.getName());
    /**
     * Shared instance of the all-zero vector (0,0,0). Do not modify!
     */
    public final static Vector3f ZERO = new Vector3f(0, 0, 0);
    /**
     * Shared instance of the all-NaN vector (NaN,NaN,NaN). Do not modify!
     */
    public final static Vector3f NAN = new Vector3f(Float.NaN, Float.NaN, Float.NaN);
    /**
     * Shared instance of the +X direction (1,0,0). Do not modify!
     */
    public final static Vector3f UNIT_X = new Vector3f(1, 0, 0);
    /**
     * Shared instance of the +Y direction (0,1,0). Do not modify!
     */
    public final static Vector3f UNIT_Y = new Vector3f(0, 1, 0);
    /**
     * Shared instance of the +Z direction (0,0,1). Do not modify!
     */
    public final static Vector3f UNIT_Z = new Vector3f(0, 0, 1);
    /**
     * Shared instance of the all-ones vector (1,1,1). Do not modify!
     */
    public final static Vector3f UNIT_XYZ = new Vector3f(1, 1, 1);
    /**
     * Shared instance of the all-plus-infinity vector (+Inf,+Inf,+Inf). Do not
     * modify!
     */
    public final static Vector3f POSITIVE_INFINITY = new Vector3f(
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY);
    /**
     * Shared instance of the all-negative-infinity vector (-Inf,-Inf,-Inf). Do
     * not modify!
     */
    public final static Vector3f NEGATIVE_INFINITY = new Vector3f(
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY,
            Float.NEGATIVE_INFINITY);
    /**
     * The first (X) component.
     */
    public float x;
    /**
     * The 2nd (Y) component.
     */
    public float y;
    /**
     * The 3rd (Z) component.
     */
    public float z;

    /**
     * Instantiates an all-zero vector (0,0,0).
     */
    public Vector3f() {
        x = y = z = 0;
    }

    /**
     * Instantiates a vector with specified components.
     *
     * @param x the desired X component
     * @param y the desired Y component
     * @param z the desired Z component
     */
    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    /**
     * Instantiates a copy of the argument.
     *
     * @param copy the vector to copy (not null, unaffected)
     */
    public Vector3f(Vector3f copy) {
        this.set(copy);
    }

    /**
     * Sets all 3 components to specified values.
     *
     * @param x the desired X component
     * @param y the desired Y component
     * @param z the desired Z component
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /**
     * Copies all 3 components from the argument.
     *
     * @param vect the Vector3f to copy (not null, unaffected)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f set(Vector3f vect) {
        this.x = vect.x;
        this.y = vect.y;
        this.z = vect.z;
        return this;
    }

    /**
     * Adds the argument and returns the sum as a new instance. If the argument
     * is null, null is returned. Either way, the current instance is
     * unaffected.
     *
     * @param vec the vector to add (unaffected) or null for none
     * @return a new Vector3f or null
     */
    public Vector3f add(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        return new Vector3f(x + vec.x, y + vec.y, z + vec.z);
    }

    /**
     * Adds a specified vector and returns the sum in a 3rd vector. The current
     * instance is unaffected unless it's <code>result</code>.
     *
     * @param vec the vector to add (not null, unaffected unless it's
     *     <code>result</code>)
     * @param result storage for the sum (not null)
     * @return <code>result</code> (for chaining)
     */
    public Vector3f add(Vector3f vec, Vector3f result) {
        result.x = x + vec.x;
        result.y = y + vec.y;
        result.z = z + vec.z;
        return result;
    }

    /**
     * Adds the argument and returns the (modified) current instance. If the
     * argument is null, null is returned.
     *
     * @param vec the vector to add (unaffected unless it's <code>this</code>)
     *     or null for none
     * @return the (modified) current instance or null
     */
    public Vector3f addLocal(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        x += vec.x;
        y += vec.y;
        z += vec.z;
        return this;
    }

    /**
     * Adds specified amounts to the vector's components and returns the sum as
     * a new instance. The current instance is unaffected.
     *
     * @param addX the amount to add to the X component
     * @param addY the amount to add to the Y component
     * @param addZ the amount to add to the Z component
     * @return a new Vector3f
     */
    public Vector3f add(float addX, float addY, float addZ) {
        return new Vector3f(x + addX, y + addY, z + addZ);
    }

    /**
     * Adds specified amounts to the vector's components and returns the
     * (modified) current instance.
     *
     * @param addX the amount to add to the X component
     * @param addY the amount to add to the Y component
     * @param addZ the amount to add to the Z component
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f addLocal(float addX, float addY, float addZ) {
        x += addX;
        y += addY;
        z += addZ;
        return this;
    }

    /**
     * Multiplies by the specified scalar, adds the specified vector, and
     * returns the (modified) current instance.
     *
     * <p>this = scalar * this + add
     *
     * @param scalar the scaling factor
     * @param add the vector to add (not null, unaffected unless it's
     *     <code>this</code>)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f scaleAdd(float scalar, Vector3f add) {
        x = x * scalar + add.x;
        y = y * scalar + add.y;
        z = z * scalar + add.z;
        return this;
    }

    /**
     * Multiplies a specified vector by a specified scalar, then adds another
     * specified vector to it, before storing the result in the current
     * instance.
     *
     * <p>this = scalar * mult + add
     *
     * @param scalar the scaling factor
     * @param mult the vector to scale (not null, unaffected unless it's
     *     <code>this</code>)
     * @param add the vector to add (not null, unaffected unless it's
     *     <code>this</code>)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f scaleAdd(float scalar, Vector3f mult, Vector3f add) {
        this.x = mult.x * scalar + add.x;
        this.y = mult.y * scalar + add.y;
        this.z = mult.z * scalar + add.z;
        return this;
    }

    /**
     * Returns the dot (or inner) product with the argument. If the argument is
     * null, 0 is returned. Either way, the current instance is unaffected.
     *
     * @param vec the vector to multiply (unaffected) or null for none
     * @return the product or 0
     */
    public float dot(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, 0 returned.");
            return 0;
        }
        return x * vec.x + y * vec.y + z * vec.z;
    }

    /**
     * Calculates a cross product with the argument and returns the product as a
     * new instance. The current instance is unaffected.
     *
     * @param v the right factor (not null, unaffected)
     * @return <code>this</code> cross <code>v</code> (a new Vector3f)
     */
    public Vector3f cross(Vector3f v) {
        return cross(v, null);
    }

    /**
     * Calculates a cross product with a specified vector and returns the
     * product in a 3rd vector. The current instance is unaffected unless it's
     * <code>result</code>.
     *
     * @param v the right factor (not null, unaffected unless it's
     *     <code>result</code>)
     * @param result storage for the product, or null for a new Vector3f
     * @return <code>this</code> cross <code>v</code> (either
     *     <code>result</code> or a new Vector3f)
     */
    public Vector3f cross(Vector3f v, Vector3f result) {
        return cross(v.x, v.y, v.z, result);
    }

    /**
     * Calculates a cross product with specified components and returns the
     * product in the specified vector. The current instance is unaffected
     * unless it's <code>result</code>.
     *
     * @param otherX the X component of the right factor
     * @param otherY the Y component of the right factor
     * @param otherZ the Z component of the right factor
     * @param result storage for the product, or null for a new Vector3f
     * @return <code>this</code> cross <code>v</code> (either
     *     <code>result</code> or a new Vector3f)
     */
    public Vector3f cross(float otherX, float otherY, float otherZ, Vector3f result) {
        if (result == null) {
            result = new Vector3f();
        }
        float resX = y * otherZ - z * otherY;
        float resY = z * otherX - x * otherZ;
        float resZ = x * otherY - y * otherX;
        result.set(resX, resY, resZ);
        return result;
    }

    /**
     * Right multiplies by the argument (cross product) and returns the
     * (modified) current instance.
     *
     * @param v the right factor (not null, unaffected unless it's
     *     <code>this</code>)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f crossLocal(Vector3f v) {
        return crossLocal(v.x, v.y, v.z);
    }

    /**
     * Right multiplies by the specified components (cross product) and returns
     * the (modified) current instance.
     *
     * @param otherX the X component of the right factor
     * @param otherY the Y component of the right factor
     * @param otherZ the Z component of the right factor
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f crossLocal(float otherX, float otherY, float otherZ) {
        float tempx = y * otherZ - z * otherY;
        float tempy = z * otherX - x * otherZ;
        z = x * otherY - y * otherX;
        x = tempx;
        y = tempy;
        return this;
    }

    /**
     * Projects onto the argument and returns the result as a new vector. The
     * current instance is unaffected.
     *
     * @param other the vector to project onto (not null, unaffected)
     * @return a new Vector3f
     */
    public Vector3f project(Vector3f other) {
        float n = this.dot(other); // A . B
        float d = other.lengthSquared(); // |B|^2
        return new Vector3f(other).multLocal(n / d);
    }

    /**
     * Projects onto the argument and returns the (modified) current instance.
     *
     * @param other the vector to project onto (not null, unaffected unless it's
     *     <code>this</code>)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f projectLocal(Vector3f other) {
        float n = this.dot(other); // A . B
        float d = other.lengthSquared(); // |B|^2
        return set(other).multLocal(n / d);
    }

    /**
     * Tests for a unit vector, with 1% tolerance. The current instance is
     * unaffected.
     *
     * @return true if the current vector's length is between 0.99 and 1.01
     *     inclusive, otherwise false
     */
    public boolean isUnitVector() {
        float len = length();
        return 0.99f < len && len < 1.01f;
    }

    /**
     * Returns the length (or magnitude). The current instance is unaffected.
     *
     * @return the root-sum of the squared components (not negative)
     */
    public float length() {
        /*
         * Use double-precision arithmetic to reduce the chance of overflow
         * (when lengthSquared > Float.MAX_VALUE) or underflow (when
         * lengthSquared is < Float.MIN_VALUE).
         */
        double xx = x;
        double yy = y;
        double zz = z;
        double lengthSquared = xx * xx + yy * yy + zz * zz;
        float result = (float) Math.sqrt(lengthSquared);

        return result;
    }

    /**
     * Returns the square of the length. The current instance is unaffected.
     *
     * @return the sum of the squared components (not negative)
     */
    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    /**
     * Returns the square of the distance between this vector and the argument.
     * The current instance is unaffected.
     *
     * @param v the vector to compare (not null, unaffected)
     * @return the square of the Euclidean distance (not negative)
     */
    public float distanceSquared(Vector3f v) {
        double dx = x - v.x;
        double dy = y - v.y;
        double dz = z - v.z;
        return (float) (dx * dx + dy * dy + dz * dz);
    }

    /**
     * Returns the distance between this vector and the argument. The current
     * instance is unaffected.
     *
     * @param v the vector to compare (not null, unaffected)
     * @return the Euclidean distance (not negative)
     */
    public float distance(Vector3f v) {
        /*
         * Use double-precision arithmetic to reduce the chance of overflow
         * (when distanceSquared > Float.MAX_VALUE) or underflow (when
         * distanceSquared is < Float.MIN_VALUE).
         */
        double dx = x - v.x;
        double dy = y - v.y;
        double dz = z - v.z;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        float result = (float) Math.sqrt(distanceSquared);

        return result;
    }

    /**
     * Multiplies with the argument and returns the product as a new instance.
     * The current instance is unaffected.
     *
     * @param scalar the scaling factor
     * @return a new Vector3f
     */
    public Vector3f mult(float scalar) {
        return new Vector3f(x * scalar, y * scalar, z * scalar);
    }

    /**
     * Multiplies with the specified scalar and returns the product in the
     * specified vector. The current instance is unaffected, unless it's
     * <code>product</code>.
     *
     * @param scalar the scaling factor
     * @param product storage for the product, or null for a new Vector3f
     * @return either <code>product</code> or a new Vector3f
     */
    public Vector3f mult(float scalar, Vector3f product) {
        if (null == product) {
            product = new Vector3f();
        }

        product.x = x * scalar;
        product.y = y * scalar;
        product.z = z * scalar;
        return product;
    }

    /**
     * Multiplies by the argument and returns the (modified) current instance.
     *
     * @param scalar the scaling factor
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f multLocal(float scalar) {
        x *= scalar;
        y *= scalar;
        z *= scalar;
        return this;
    }

    /**
     * Multiplies component-wise by the argument and returns the (modified)
     * current instance. If the argument is null, null is returned.
     *
     * @param vec the scale vector (unaffected unless it's <code>this</code>) or
     *     null for none
     * @return the (modified) current instance (for chaining) or null
     */
    public Vector3f multLocal(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        x *= vec.x;
        y *= vec.y;
        z *= vec.z;
        return this;
    }

    /**
     * Multiplies component-wise by the specified components and returns the
     * (modified) current instance.
     *
     * @param x the scale factor for the X component
     * @param y the scale factor for the Y component
     * @param z the scale factor for the Z component
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f multLocal(float x, float y, float z) {
        this.x *= x;
        this.y *= y;
        this.z *= z;
        return this;
    }

    /**
     * Multiplies component-wise with the argument and returns the product as a
     * new instance. If the argument is null, null is returned. Either way, the
     * current instance is unaffected.
     *
     * @param vec the scale vector (unaffected) or null for none
     * @return a new Vector3f or null
     */
    public Vector3f mult(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        return mult(vec, null);
    }

    /**
     * Multiplies component-wise by the specified components and returns the
     * product as a new instance. The current instance is unaffected.
     *
     * @param x the scale factor for the X component
     * @param y the scale factor for the Y component
     * @param z the scale factor for the Z component
     * @return a new Vector3f
     */
    public Vector3f mult(float x, float y, float z) {
        return new Vector3f(this.x * x, this.y * y, this.z * z);
    }

    /**
     * Multiplies component-wise with the specified vector and returns the
     * product in a 3rd vector. If the argument is null, null is returned.
     * Either way, the current instance is unaffected, unless it's
     * <code>store</code>.
     *
     * @param vec the scale vector (unaffected unless it's <code>store</code>)
     *     or null for none
     * @param store storage for the product, or null for a new Vector3f
     * @return either <code>store</code> or a new Vector3f or null
     */
    public Vector3f mult(Vector3f vec, Vector3f store) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        if (store == null) {
            store = new Vector3f();
        }
        return store.set(x * vec.x, y * vec.y, z * vec.z);
    }

    /**
     * Divides by the argument and returns the quotient as a new instance. The
     * current instance is unaffected.
     *
     * @param scalar the divisor
     * @return a new Vector3f
     */
    public Vector3f divide(float scalar) {
        scalar = 1f / scalar;
        return new Vector3f(x * scalar, y * scalar, z * scalar);
    }

    /**
     * Divides by the argument and returns the (modified) current instance.
     *
     * @param scalar the divisor
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f divideLocal(float scalar) {
        scalar = 1f / scalar;
        x *= scalar;
        y *= scalar;
        z *= scalar;
        return this;
    }

    /**
     * Divides component-wise by the specified components returns the (modified)
     * current instance.
     *
     * @param x the divisor for the X component
     * @param y the divisor for the Y component
     * @param z the divisor for the Z component
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f divideLocal(float x, float y, float z) {
        this.x /= x;
        this.y /= y;
        this.z /= z;
        return this;
    }

    /**
     * Divides component-wise by the argument and returns the quotient as a new
     * instance. The current instance is unaffected.
     *
     * @param divisor the divisor (not null, unaffected)
     * @return a new Vector3f
     */
    public Vector3f divide(Vector3f divisor) {
        return new Vector3f(x / divisor.x, y / divisor.y, z / divisor.z);
    }

    /**
     * Divides component-wise by the specified components and returns the quotient
     * as a new instance. The current instance is unaffected.
     *
     * @param x the divisor for the X component
     * @param y the divisor for the Y component
     * @param z the divisor for the Z component
     * @return a new Vector3f
     */
    public Vector3f divide(float x, float y, float z) {
        return new Vector3f(this.x / x, this.y / y, this.z / z);
    }

    /**
     * Divides component-wise by the argument and returns the (modified) current
     * instance.
     *
     * @param divisor the divisor (not null, unaffected)
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f divideLocal(Vector3f divisor) {
        x /= divisor.x;
        y /= divisor.y;
        z /= divisor.z;
        return this;
    }

    /**
     * Returns the negative. The current instance is unaffected.
     *
     * @return a new Vector3f
     */
    public Vector3f negate() {
        return new Vector3f(-x, -y, -z);
    }

    /**
     * Negates all 3 components and returns the (modified) current instance.
     *
     * @return the (modified) current instance (for chaining)
     */
    public Vector3f negateLocal() {
        x = -x;
        y = -y;
        z = -z;
        return this;
    }

    public Vector3f subtract(Vector3f vec) {
        return new Vector3f(x - vec.x, y - vec.y, z - vec.z);
    }

    public Vector3f subtractLocal(Vector3f vec) {
        if (null == vec) {
            logger.warning("Provided vector is null, null returned.");
            return null;
        }
        x -= vec.x;
        y -= vec.y;
        z -= vec.z;
        return this;
    }

    public Vector3f subtract(Vector3f vec, Vector3f result) {
        if (result == null) {
            result = new Vector3f();
        }
        result.x = x - vec.x;
        result.y = y - vec.y;
        result.z = z - vec.z;
        return result;
    }

    public Vector3f subtract(float subtractX, float subtractY, float subtractZ) {
        return new Vector3f(x - subtractX, y - subtractY, z - subtractZ);
    }

    public Vector3f subtractLocal(float subtractX, float subtractY, float subtractZ) {
        x -= subtractX;
        y -= subtractY;
        z -= subtractZ;
        return this;
    }

    public Vector3f normalize() {
//        float length = length();
//        if (length != 0) {
//            return divide(length);
//        }
//
//        return divide(1);
        float length = x * x + y * y + z * z;
        if (length != 1f && length != 0f) {
            length = 1.0f / (float) Math.sqrt(length);
            return new Vector3f(x * length, y * length, z * length);
        }
        return clone();
    }

    public Vector3f normalizeLocal() {
        // NOTE: this implementation is more optimized
        // than the old jme normalize as this method
        // is commonly used.
        float length = x * x + y * y + z * z;
        if (length != 1f && length != 0f) {
            length = 1.0f / (float) Math.sqrt(length);
            x *= length;
            y *= length;
            z *= length;
        }
        return this;
    }

    public Vector3f maxLocal(Vector3f other) {
        x = other.x > x ? other.x : x;
        y = other.y > y ? other.y : y;
        z = other.z > z ? other.z : z;
        return this;
    }

    public Vector3f minLocal(Vector3f other) {
        x = other.x < x ? other.x : x;
        y = other.y < y ? other.y : y;
        z = other.z < z ? other.z : z;
        return this;
    }

    public Vector3f zero() {
        x = y = z = 0;
        return this;
    }

    public float angleBetween(Vector3f otherVector) {
        float dotProduct = dot(otherVector);
        if (-1.0f < dotProduct) {
            if (dotProduct < 1.0f) {
                return (float) Math.acos(dotProduct);
            }

            return 0.0f;
        }

        return (float) Math.PI;
    }

    public Vector3f interpolateLocal(Vector3f finalVec, float changeAmount) {
        this.x = (1 - changeAmount) * this.x + changeAmount * finalVec.x;
        this.y = (1 - changeAmount) * this.y + changeAmount * finalVec.y;
        this.z = (1 - changeAmount) * this.z + changeAmount * finalVec.z;
        return this;
    }

    public Vector3f interpolateLocal(Vector3f beginVec, Vector3f finalVec, float changeAmount) {
        this.x = (1 - changeAmount) * beginVec.x + changeAmount * finalVec.x;
        this.y = (1 - changeAmount) * beginVec.y + changeAmount * finalVec.y;
        this.z = (1 - changeAmount) * beginVec.z + changeAmount * finalVec.z;
        return this;
    }

    public static boolean isValidVector(Vector3f vector) {
        if (vector == null) {
            return false;
        }
        if (Float.isNaN(vector.x)
                || Float.isNaN(vector.y)
                || Float.isNaN(vector.z)) {
            return false;
        }
        return !Float.isInfinite(vector.x)
                && !Float.isInfinite(vector.y)
                && !Float.isInfinite(vector.z);
    }

    public static void generateOrthonormalBasis(Vector3f u, Vector3f v, Vector3f w) {
        w.normalizeLocal();
        generateComplementBasis(u, v, w);
    }

    public static void generateComplementBasis(Vector3f u, Vector3f v,
                                               Vector3f w) {
        float fInvLength;

        if (Math.abs(w.x) >= Math.abs(w.y)) {
            // w.x or w.z is the largest magnitude component, swap them
            fInvLength = (float) (1.0f / Math.sqrt(w.x * w.x + w.z * w.z));
            u.x = -w.z * fInvLength;
            u.y = 0.0f;
            u.z = +w.x * fInvLength;
            v.x = w.y * u.z;
            v.y = w.z * u.x - w.x * u.z;
            v.z = -w.y * u.x;
        } else {
            // w.y or w.z is the largest magnitude component, swap them
            fInvLength = (float) (1.0f / Math.sqrt(w.y * w.y + w.z * w.z));
            u.x = 0.0f;
            u.y = +w.z * fInvLength;
            u.z = -w.y * fInvLength;
            v.x = w.y * u.z - w.z * u.y;
            v.y = -w.x * u.z;
            v.z = w.x * u.y;
        }
    }

    @Override
    public Vector3f clone() {
        try {
            return (Vector3f) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(); // can not happen
        }
    }

    public float[] toArray(float[] floats) {
        if (floats == null) {
            floats = new float[3];
        }
        floats[0] = x;
        floats[1] = y;
        floats[2] = z;
        return floats;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Vector3f comp)) {
            return false;
        }

        if (this == o) {
            return true;
        }

        if (Float.compare(x, comp.x) != 0) {
            return false;
        }
        if (Float.compare(y, comp.y) != 0) {
            return false;
        }
        return Float.compare(z, comp.z) == 0;
    }

    public boolean isSimilar(Vector3f other, float epsilon) {
        if (other == null) {
            return false;
        }
        if (Float.compare(Math.abs(other.x - x), epsilon) > 0) {
            return false;
        }
        if (Float.compare(Math.abs(other.y - y), epsilon) > 0) {
            return false;
        }
        return Float.compare(Math.abs(other.z - z), epsilon) <= 0;
    }

    @Override
    public int hashCode() {
        int hash = 37;
        hash += 37 * hash + Float.floatToIntBits(x);
        hash += 37 * hash + Float.floatToIntBits(y);
        hash += 37 * hash + Float.floatToIntBits(z);
        return hash;
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }

    public float getX() {
        return x;
    }

    public Vector3f setX(float x) {
        this.x = x;
        return this;
    }

    public float getY() {
        return y;
    }

    public Vector3f setY(float y) {
        this.y = y;
        return this;
    }

    public float getZ() {
        return z;
    }

    public Vector3f setZ(float z) {
        this.z = z;
        return this;
    }

    public float get(int index) {
        switch (index) {
            case 0:
                return x;
            case 1:
                return y;
            case 2:
                return z;
        }
        throw new IllegalArgumentException("index must be either 0, 1 or 2");
    }

    public void set(int index, float value) {
        switch (index) {
            case 0:
                x = value;
                return;
            case 1:
                y = value;
                return;
            case 2:
                z = value;
                return;
        }
        throw new IllegalArgumentException("index must be either 0, 1 or 2");
    }
}
