package org.saket.eventbooking.booking.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.RequiredArgsConstructor;
import org.saket.eventbooking.booking.entity.Booking;
import org.saket.eventbooking.booking.enums.BookingStatus;
import org.saket.eventbooking.booking.repository.BookingRepository;
import org.saket.eventbooking.common.exception.ConflictException;
import org.saket.eventbooking.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;

/**
 * One QR per confirmed booking, encoding its booking reference. Rendered on demand rather than
 * stored: it's a pure function of the reference, so there's nothing to keep in sync.
 */
@Service
@RequiredArgsConstructor
public class BookingQrService {

    private static final int SIZE_PX = 320;

    private final BookingRepository bookingRepository;

    /** PNG for the caller's own CONFIRMED booking; 404 if not theirs, 409 if not confirmed. */
    @Transactional(readOnly = true)
    public byte[] qrPngForOwner(UUID userId, UUID bookingId) {
        Booking booking = bookingRepository.findByIdAndUserId(bookingId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        if (booking.getStatus() != BookingStatus.CONFIRMED) {
            throw new ConflictException("Tickets are issued once the booking is confirmed");
        }
        return render(booking.getBookingReference());
    }

    static byte[] render(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 2));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", png);
            return png.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Could not encode QR code", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
