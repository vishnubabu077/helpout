package com.instanthelp.helper;

import com.instanthelp.common.BusinessRuleException;
import com.instanthelp.common.ConflictException;
import com.instanthelp.common.ForbiddenException;
import com.instanthelp.common.NotFoundException;
import com.instanthelp.identity.AppUser;
import com.instanthelp.identity.AppUserRepository;
import com.instanthelp.identity.UserStatus;
import com.instanthelp.task.TaskCategory;
import com.instanthelp.task.TaskCategoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The helper's own availability lifecycle. The helper id always comes from the JWT, never from the request. */
@Service
public class HelperAvailabilityService {

    private final HelperAvailabilityRepository repository;
    private final HelperAvailabilityEvents events;
    private final AppUserRepository users;
    private final TaskCategoryRepository categories;

    public HelperAvailabilityService(HelperAvailabilityRepository repository, HelperAvailabilityEvents events,
                                     AppUserRepository users, TaskCategoryRepository categories) {
        this.repository = repository;
        this.events = events;
        this.users = users;
        this.categories = categories;
    }

    @Transactional
    public AvailabilityResponse goAvailable(UUID helperId, GoAvailableRequest req) {
        AppUser helper = users.findById(helperId).orElseThrow(() -> new ForbiddenException("Account not found"));
        if (helper.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Account is not active");
        }
        List<String> codes = req.categoryCodes().stream().map(String::trim).distinct().toList();
        List<String> known = categories.findAllById(codes).stream()
                .filter(TaskCategory::isActive).map(TaskCategory::getCode).toList();
        List<String> unknown = codes.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("UNSUPPORTED_CATEGORY", "Unsupported task categories: " + unknown);
        }
        if (repository.findActiveByHelper(helperId).isPresent()) {
            throw alreadyAvailable();
        }

        HelperAvailability row;
        try {
            row = repository.insertAvailable(helperId, req.latitude().doubleValue(), req.longitude().doubleValue(),
                    codes, req.maxDistanceM());
        } catch (DuplicateKeyException race) {
            throw alreadyAvailable(); // two concurrent "go available" calls; the partial unique index decided
        }
        events.emit(AvailabilityEventType.HelperAvailable, row);
        return AvailabilityResponse.from(row);
    }

    @Transactional
    public AvailabilityResponse updateLocation(UUID helperId, LocationUpdateRequest req) {
        HelperAvailability row = repository
                .updateLocation(helperId, req.latitude().doubleValue(), req.longitude().doubleValue())
                .orElseThrow(HelperAvailabilityService::notAvailable);
        events.emit(AvailabilityEventType.HelperLocationUpdated, row);
        return AvailabilityResponse.from(row);
    }

    /** Only allowed while AVAILABLE; a helper holding an offer or a task cannot just disappear. */
    @Transactional
    public void goOffline(UUID helperId) {
        var row = repository.goOffline(helperId);
        if (row.isPresent()) {
            events.emit(AvailabilityEventType.HelperUnavailable, row.get());
            return;
        }
        if (repository.findActiveByHelper(helperId).isPresent()) {
            throw new ConflictException("HELPER_BUSY", "You have an open offer or an assigned task");
        }
        throw notAvailable();
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse current(UUID helperId) {
        return repository.findActiveByHelper(helperId).map(AvailabilityResponse::from)
                .orElseGet(AvailabilityResponse::offline);
    }

    private static ConflictException alreadyAvailable() {
        return new ConflictException("ALREADY_AVAILABLE", "You are already available");
    }

    private static NotFoundException notAvailable() {
        return new NotFoundException("NOT_AVAILABLE", "You are not available");
    }
}
