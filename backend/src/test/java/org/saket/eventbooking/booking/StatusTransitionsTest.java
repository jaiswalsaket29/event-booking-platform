package org.saket.eventbooking.booking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.common.exception.IllegalStatusTransitionException;
import org.saket.eventbooking.payment.entity.Payment;
import org.saket.eventbooking.payment.enums.PaymentStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The booking and payment lifecycles: PENDING fans out to terminal states, nothing moves backward. */
class StatusTransitionsTest {

    @Test
    void pendingBookingCanReachEveryTerminalState() {
        for (BookingStatus next : new BookingStatus[]{BookingStatus.CONFIRMED, BookingStatus.FAILED, BookingStatus.CANCELLED}) {
            Booking booking = new Booking();
            booking.transitionTo(BookingStatus.PENDING);
            booking.transitionTo(next);
            assertThat(booking.getStatus()).isEqualTo(next);
            assertThat(next.isTerminal()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CONFIRMED", "FAILED", "CANCELLED"})
    void terminalBookingStatesNeverChange(BookingStatus terminal) {
        Booking booking = new Booking();
        booking.transitionTo(BookingStatus.PENDING);
        booking.transitionTo(terminal);
        for (BookingStatus next : BookingStatus.values()) {
            assertThatThrownBy(() -> booking.transitionTo(next)).isInstanceOf(IllegalStatusTransitionException.class);
        }
        assertThat(booking.getStatus()).isEqualTo(terminal);
    }

    @Test
    void newBookingsMustStartPending() {
        assertThatThrownBy(() -> new Booking().transitionTo(BookingStatus.CONFIRMED))
                .isInstanceOf(IllegalStatusTransitionException.class);
        Booking booking = new Booking();
        booking.transitionTo(BookingStatus.PENDING);
        assertThatThrownBy(() -> booking.transitionTo(BookingStatus.PENDING))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }

    @Test
    void paymentGoesFromPendingToSuccessOrFailedOnly() {
        Payment ok = new Payment();
        ok.transitionTo(PaymentStatus.PENDING);
        ok.transitionTo(PaymentStatus.SUCCESS);
        assertThatThrownBy(() -> ok.transitionTo(PaymentStatus.FAILED)).isInstanceOf(IllegalStatusTransitionException.class);
        assertThatThrownBy(() -> ok.transitionTo(PaymentStatus.REFUNDED)).isInstanceOf(IllegalStatusTransitionException.class);

        Payment declined = new Payment();
        declined.transitionTo(PaymentStatus.PENDING);
        declined.transitionTo(PaymentStatus.FAILED);
        assertThatThrownBy(() -> declined.transitionTo(PaymentStatus.SUCCESS)).isInstanceOf(IllegalStatusTransitionException.class);

        assertThatThrownBy(() -> new Payment().transitionTo(PaymentStatus.SUCCESS))
                .isInstanceOf(IllegalStatusTransitionException.class);
    }
}
