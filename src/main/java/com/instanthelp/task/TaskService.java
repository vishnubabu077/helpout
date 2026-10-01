package com.instanthelp.task;

import com.instanthelp.common.BusinessRuleException;
import com.instanthelp.common.ConflictException;
import com.instanthelp.common.ForbiddenException;
import com.instanthelp.common.NotFoundException;
import com.instanthelp.common.PagedResponse;
import com.instanthelp.config.AppProperties;
import com.instanthelp.identity.AppUser;
import com.instanthelp.identity.AppUserRepository;
import com.instanthelp.identity.UserStatus;
import com.instanthelp.outbox.OutboxService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final TaskCategoryRepository categories;
    private final AppUserRepository users;
    private final AppProperties props;
    private final OutboxService outbox;
    private final ApplicationEventPublisher publisher;

    public TaskService(TaskRepository tasks, TaskEventRepository events, TaskCategoryRepository categories,
                       AppUserRepository users, AppProperties props, OutboxService outbox,
                       ApplicationEventPublisher publisher) {
        this.tasks = tasks;
        this.events = events;
        this.categories = categories;
        this.users = users;
        this.props = props;
        this.outbox = outbox;
        this.publisher = publisher;
    }

    /** Validates business rules server-side, then publishes the task as OPEN and records an audit event. */
    @Transactional
    public TaskResponse create(UUID requesterId, CreateTaskRequest req) {
        AppUser requester = users.findById(requesterId)
                .orElseThrow(() -> new ForbiddenException("Account not found"));
        if (requester.getStatus() != UserStatus.ACTIVE) {
            throw new ForbiddenException("Account is not active");
        }

        categories.findById(req.categoryCode())
                .filter(TaskCategory::isActive)
                .orElseThrow(() -> new BusinessRuleException("UNSUPPORTED_CATEGORY",
                        "Unsupported task category: " + req.categoryCode()));

        if (!req.endTime().isAfter(req.startTime())) {
            throw new BusinessRuleException("INVALID_TIME_WINDOW", "endTime must be after startTime");
        }
        Duration minimum = Duration.ofMinutes(props.task().minDurationMinutes());
        if (Duration.between(req.startTime(), req.endTime()).compareTo(minimum) < 0) {
            throw new BusinessRuleException("TASK_TOO_SHORT",
                    "Time window must be at least " + props.task().minDurationMinutes() + " minutes");
        }

        String currency = req.currency() != null ? req.currency() : props.task().defaultCurrency();
        Task task = tasks.save(new Task(
                requesterId, req.title().trim(), req.description(), req.categoryCode(), req.address().trim(),
                req.latitude().doubleValue(), req.longitude().doubleValue(),
                req.startTime(), req.endTime(), req.offeredAmount(), currency, TaskStatus.OPEN));
        events.save(new TaskEvent(task.getId(), requesterId, "TASK_CREATED", null, TaskStatus.OPEN));
        return TaskResponse.from(task);
    }

    /**
     * OPEN -> MATCHING with the reserved helper. Joins the caller's transaction (the reservation and the outbox
     * event commit or roll back together with this). The version check makes two racing transitions of the same
     * task end in a 409 for the loser.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void offerTo(UUID taskId, UUID actorId, UUID helperId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
        if (task.getStatus() != TaskStatus.OPEN) {
            throw new ConflictException("TASK_NOT_OPEN", "Task is " + task.getStatus() + ", not OPEN");
        }
        task.offerTo(helperId);
        tasks.saveAndFlush(task);
        events.save(new TaskEvent(taskId, actorId, "HELPER_OFFERED", TaskStatus.OPEN, TaskStatus.MATCHING));
    }

    /** MATCHING -> ASSIGNED when the offered helper accepts. Writes the TaskAssigned outbox event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void offerAccepted(UUID taskId, UUID helperId) {
        Task task = tasks.findById(taskId).orElseThrow(() -> new NotFoundException("Task not found"));
        if (task.getStatus() != TaskStatus.MATCHING || !helperId.equals(task.getProviderId())) {
            throw new ConflictException("OFFER_EXPIRED", "The offer is no longer valid");
        }
        task.assign();
        tasks.saveAndFlush(task);
        events.save(new TaskEvent(taskId, helperId, "HELPER_ACCEPTED", TaskStatus.MATCHING, TaskStatus.ASSIGNED));
        outbox.append("TASK", taskId, "TaskAssigned", taskId.toString(),
                new TaskOutboxEvent(taskId, task.getRequesterId(), helperId, TaskStatus.ASSIGNED, Instant.now()));
    }

    /**
     * MATCHING -> OPEN after the helper declined or the offer expired. Does nothing if the task moved on in the
     * meantime (e.g. the requester cancelled it).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void offerReleased(UUID taskId, UUID helperId, String eventType) {
        Task task = tasks.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != TaskStatus.MATCHING || !helperId.equals(task.getProviderId())) {
            return;
        }
        task.reopen();
        tasks.saveAndFlush(task);
        events.save(new TaskEvent(taskId, helperId, eventType, TaskStatus.MATCHING, TaskStatus.OPEN));
    }

    /** The requester cancels. Allowed until the task is finished; frees any helper holding an offer or the task. */
    @Transactional
    public TaskResponse cancel(UUID requesterId, UUID taskId) {
        Task task = tasks.findByIdAndRequesterId(taskId, requesterId)
                .orElseThrow(() -> new NotFoundException("Task not found"));
        TaskStatus from = task.getStatus();
        if (from != TaskStatus.OPEN && from != TaskStatus.MATCHING && from != TaskStatus.ASSIGNED) {
            throw new ConflictException("TASK_NOT_CANCELLABLE", "A task that is " + from + " cannot be cancelled");
        }
        UUID helperId = task.getProviderId();
        task.cancel();
        tasks.saveAndFlush(task);
        events.save(new TaskEvent(taskId, requesterId, "TASK_CANCELLED", from, TaskStatus.CANCELLED));
        outbox.append("TASK", taskId, "TaskCancelled", taskId.toString(),
                new TaskOutboxEvent(taskId, requesterId, helperId, TaskStatus.CANCELLED, Instant.now()));
        publisher.publishEvent(new TaskClosedEvent(taskId, TaskStatus.CANCELLED));
        return TaskResponse.from(task);
    }

    /** Owner-only for now. Provider visibility of OPEN tasks arrives with discovery. Returns 404 to non-owners. */
    @Transactional(readOnly = true)
    public TaskResponse getOwned(UUID requesterId, UUID taskId) {
        return tasks.findByIdAndRequesterId(taskId, requesterId)
                .map(TaskResponse::from)
                .orElseThrow(() -> new NotFoundException("Task not found"));
    }

    @Transactional(readOnly = true)
    public PagedResponse<TaskResponse> listMine(UUID requesterId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Task> result = tasks.findByRequesterId(requesterId, pageable);
        List<TaskResponse> items = result.getContent().stream().map(TaskResponse::from).toList();
        return new PagedResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categories() {
        return categories.findByActiveTrueOrderByNameAsc().stream()
                .map(c -> new CategoryResponse(c.getCode(), c.getName()))
                .toList();
    }
}
