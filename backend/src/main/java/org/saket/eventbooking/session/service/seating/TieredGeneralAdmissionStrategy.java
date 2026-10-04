package org.saket.eventbooking.session.service.seating;

import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.common.exception.BadRequestException;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.saket.eventbooking.session.entity.Session;
import org.saket.eventbooking.session.entity.TicketTier;
import org.saket.eventbooking.session.enums.PricingMode;
import org.saket.eventbooking.session.enums.SeatingType;
import org.saket.eventbooking.session.repository.TicketTierRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** General admission with a capacity counter per ticket tier. One tier per booking. */
@Component
@RequiredArgsConstructor
public class TieredGeneralAdmissionStrategy implements SeatingStrategy {

    private final TicketTierRepository ticketTierRepository;

    @Override
    public boolean supports(Session session) {
        return session.getSeatingType() == SeatingType.GENERAL_ADMISSION && session.getPricingMode() == PricingMode.TIERED;
    }

    @Override
    public Hold hold(Session session, HoldRequest request) {
        if (request.ticketTierId() == null) {
            throw new BadRequestException("ticketTierId is required for this session");
        }
        if (!request.seatIds().isEmpty()) {
            throw new BadRequestException("This session sells unassigned tickets: send a tier and quantity, not seats");
        }
        int quantity = SeatingRules.requireQuantity(request.quantity());

        TicketTier tier = ticketTierRepository.lockByIdAndSessionId(request.ticketTierId(), session.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Ticket tier", request.ticketTierId()));
        if (tier.getAvailableCapacity() < quantity) {
            throw new ConflictException(tier.getName() + ": "
                    + FlatGeneralAdmissionStrategy.soldOutMessage(tier.getAvailableCapacity()).toLowerCase());
        }
        tier.setAvailableCapacity(tier.getAvailableCapacity() - quantity);

        return new Hold(session, tier, quantity, List.of(), tier.getPrice().multiply(BigDecimal.valueOf(quantity)));
    }

    @Override
    public void release(Session session, HeldInventory request) {
        ticketTierRepository.lockByIdAndSessionId(request.ticketTierId(), session.getId()).ifPresent(tier ->
                // Never above total: an admin may have shrunk the tier while the hold was open.
                tier.setAvailableCapacity(Math.min(tier.getTotalCapacity(),
                        tier.getAvailableCapacity() + request.quantity())));
    }
}
