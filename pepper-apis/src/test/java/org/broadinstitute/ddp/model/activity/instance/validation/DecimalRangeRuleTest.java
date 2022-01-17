package org.broadinstitute.ddp.model.activity.instance.validation;

import org.broadinstitute.ddp.model.activity.instance.answer.NumericDecimalAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericIntegerAnswer;
import org.broadinstitute.ddp.model.activity.instance.question.NumericQuestion;
import org.broadinstitute.ddp.model.activity.types.NumericType;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.Assert.*;

public class DecimalRangeRuleTest {

    private static NumericQuestion unused;

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @BeforeClass
    public static void setup() {
        unused = new NumericQuestion("sid", 1L, 2L, false, false, false, null, null, null, List.of(), List.of(), NumericType.DECIMAL);
    }

    @Test
    public void testChecksRange() {
        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("range");
        DecimalRangeRule.of("msg", null, false, BigDecimal.valueOf(5L), BigDecimal.valueOf(3L));
    }

    @Test
    public void testValidate_noValue() {
        DecimalRangeRule rule = DecimalRangeRule.of("msg", "hint", false, BigDecimal.ONE, BigDecimal.TEN);
        assertFalse(rule.validate(unused, null));
        assertTrue(rule.validate(unused, new NumericDecimalAnswer(1L, "q", "a", null)));
    }

    @Test
    public void testValidate_onlyMinimum() {
        DecimalRangeRule rule = DecimalRangeRule.of("msg", "hint", false, BigDecimal.valueOf(3L), null);
        assertEquals(false, run(rule, BigDecimal.ONE));
        assertEquals(true, run(rule, BigDecimal.valueOf(3L)));
        assertEquals(true, run(rule, BigDecimal.valueOf(12345L)));
    }

    @Test
    public void testValidate_onlyMaximum() {
        DecimalRangeRule rule = DecimalRangeRule.of("msg", "hint", false, null, BigDecimal.valueOf(5L));
        assertEquals(true, run(rule, BigDecimal.ONE));
        assertEquals(true, run(rule, BigDecimal.valueOf(5L)));
        assertEquals(false, run(rule, BigDecimal.valueOf(12345L)));
    }

    @Test
    public void testValidate_exactly() {
        DecimalRangeRule rule = DecimalRangeRule.of("msg", "hint", false, BigDecimal.valueOf(3L), BigDecimal.valueOf(3L));
        assertEquals(false, run(rule, BigDecimal.ONE));
        assertEquals(false, run(rule,BigDecimal.valueOf(12L)));
        assertEquals(true, run(rule, BigDecimal.valueOf(3L)));
    }

    @Test
    public void testValidate_range() {
        DecimalRangeRule rule = DecimalRangeRule.of("msg", "hint", false, BigDecimal.valueOf(3L), BigDecimal.valueOf(5L));
        assertEquals(false, run(rule, BigDecimal.ONE));
        assertEquals(false, run(rule, BigDecimal.valueOf(2L)));
        assertEquals(true, run(rule, BigDecimal.valueOf(3L)));
        assertEquals(true, run(rule, BigDecimal.valueOf(4L)));
        assertEquals(true, run(rule, BigDecimal.valueOf(5L)));
        assertEquals(false, run(rule, BigDecimal.valueOf(12345L)));
    }

    // Helper to run the rule.
    private boolean run(DecimalRangeRule rule, BigDecimal value) {
        return rule.validate(unused, new NumericDecimalAnswer(1L, "q", "a", value));
    }
}
