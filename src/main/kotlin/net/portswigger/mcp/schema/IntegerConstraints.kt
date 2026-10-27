package net.portswigger.mcp.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialInfo

@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ConstrainedInteger(val value: IntegerConstraint)

enum class IntegerConstraint(val minimum: Int, val maximum: Int? = null) {
    PORT(1, 65535),
    POSITIVE(1),
    NON_NEGATIVE(0);

    fun validate(name: String, value: Int) {
        require(value >= minimum && (maximum == null || value <= maximum)) {
            when {
                maximum != null -> "$name must be between $minimum and $maximum"
                minimum == 1 -> "$name must be greater than 0"
                else -> "$name must be $minimum or greater"
            }
        }
    }
}
