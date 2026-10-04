package org.saket.eventbooking.session;

import org.junit.jupiter.api.Test;
import org.saket.eventbooking.location.enums.SeatType;
import org.saket.eventbooking.session.config.SeatPricingProperties;
import org.saket.eventbooking.session.service.SeatPriceCalculator;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SeatPriceCalculatorTest {

    private final SeatPriceCalculator calculator = new SeatPriceCalculator(new SeatPricingProperties(Map.of(
            SeatType.REGULAR, new BigDecimal("1.0"),
            SeatType.PREMIUM, new BigDecimal("1.5"),
            SeatType.RECLINER, new BigDecimal("2.0"))));

    @Test
    void appliesMultiplierPerSeatType() {
        BigDecimal base = new BigDecimal("250.00");
        assertThat(calculator.priceFor(SeatType.REGULAR, base)).isEqualByComparingTo("250.00");
        assertThat(calculator.priceFor(SeatType.PREMIUM, base)).isEqualByComparingTo("375.00");
        assertThat(calculator.priceFor(SeatType.RECLINER, base)).isEqualByComparingTo("500.00");
    }

    @Test
    void roundsToTwoDecimalPlacesHalfUp() {
        // 199.99 * 1.5 = 299.985 -> 299.99
        BigDecimal price = calculator.priceFor(SeatType.PREMIUM, new BigDecimal("199.99"));
        assertThat(price).isEqualTo(new BigDecimal("299.99"));
        assertThat(price.scale()).isEqualTo(2);
    }

    @Test
    void failsFastWhenASeatTypeHasNoMultiplier() {
        SeatPricingProperties incomplete = new SeatPricingProperties(Map.of(SeatType.REGULAR, BigDecimal.ONE));
        assertThatThrownBy(() -> new SeatPriceCalculator(incomplete))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PREMIUM");
    }

    @Test
    void failsFastOnNonPositiveMultiplier() {
        SeatPricingProperties bad = new SeatPricingProperties(Map.of(
                SeatType.REGULAR, BigDecimal.ONE, SeatType.PREMIUM, BigDecimal.ZERO, SeatType.RECLINER, BigDecimal.TEN));
        assertThatThrownBy(() -> new SeatPriceCalculator(bad)).isInstanceOf(IllegalStateException.class);
    }
}
