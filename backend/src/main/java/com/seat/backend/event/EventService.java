package com.seat.backend.event;

import com.seat.backend.common.BusinessException;
import com.seat.backend.common.ErrorCode;
import com.seat.backend.event.dto.EventResponse;
import com.seat.backend.event.dto.SeatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepo;
    private final SeatRepository seatRepo;

    @Transactional(readOnly = true)
    public List<EventResponse> upcoming() {
        return eventRepo.findByStartAtAfterOrderByStartAtAsc(LocalDateTime.now())
                .stream().map(EventResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> seatsOf(Long eventId) {
        if (!eventRepo.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND);
        }
        return seatRepo.findByEvent_IdOrderBySeatNoAsc(eventId)
                .stream().map(SeatResponse::from).toList();
    }
}