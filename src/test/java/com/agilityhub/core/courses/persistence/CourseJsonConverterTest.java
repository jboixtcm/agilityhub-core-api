package com.agilityhub.core.courses.persistence;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** E9-T02 (carried from E9-T01's review, E98): a course JSON number that does not fit a Decimal128 falls back instead of failing. */
class CourseJsonConverterTest {
    static final JsonNodeFactory NODES = JsonNodeFactory.withExactBigDecimals(true);

    @Test void E9_T02_anExactNumberStaysADecimal128WhenItFits() {
        assertThat(CourseJsonConverter.toBson(NODES.numberNode(new BigDecimal("0.10")))).isEqualTo(new Decimal128(new BigDecimal("0.10")));
        assertThat(CourseJsonConverter.toNode(CourseJsonConverter.toBson(NODES.numberNode(new BigDecimal("0.10")))).decimalValue()).isEqualByComparingTo("0.10");
    }

    @Test void E9_T02_tooManyDigitsKeepDecimal128sOwnRounding() {
        var digits = new BigDecimal("1.23456789012345678901234567890123456789");
        var stored = (Decimal128) CourseJsonConverter.toBson(NODES.numberNode(digits));
        assertThat(stored.bigDecimalValue().precision()).isEqualTo(34);
        assertThat(stored.bigDecimalValue()).isEqualByComparingTo("1.234567890123456789012345678901235");
        var integer = new BigInteger("123456789012345678901234567890123456789");
        assertThat(CourseJsonConverter.toBson(NODES.numberNode(integer))).isInstanceOf(Decimal128.class);
        assertThat(CourseJsonConverter.storable(NODES.numberNode(digits))).isTrue();
    }

    @Test void E9_T02_anExponentOutsideDecimal128FallsBackToTheNearestDouble() {
        assertThat(CourseJsonConverter.toBson(NODES.numberNode(new BigDecimal("1E-7000")))).isEqualTo(0.0);
        assertThat(CourseJsonConverter.toBson(NODES.numberNode(new BigDecimal("1E+300")))).isInstanceOf(Decimal128.class);
        assertThat(CourseJsonConverter.storable(NODES.numberNode(new BigDecimal("1E-7000")))).isTrue();
    }

    @Test void E9_T02_onlyANumberNoDoubleCanHoldIsRefusedBeforeAnyWrite() {
        var huge = NODES.numberNode(new BigDecimal("1E+7000"));
        assertThat(CourseJsonConverter.storable(huge)).isFalse();
        assertThatThrownBy(() -> CourseJsonConverter.toBson(huge)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CourseJsonConverter.storable(NODES.numberNode(Double.POSITIVE_INFINITY))).isFalse();
        assertThatThrownBy(() -> CourseJsonConverter.toBson(NODES.numberNode(Double.NaN))).isInstanceOf(IllegalArgumentException.class);
        assertThat(CourseJsonConverter.storable(NODES.numberNode(1.5))).isTrue();
        assertThat(CourseJsonConverter.toBson(NODES.numberNode(1.5))).isEqualTo(1.5);
    }
}
