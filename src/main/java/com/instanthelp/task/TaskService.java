package com.instanthelp.task;

import com.instanthelp.common.BusinessRuleException;
import com.instanthelp.common.ForbiddenException;
import com.instanthelp.common.NotFoundException;
import com.instanthelp.common.PagedResponse;
import com.instanthelp.config.AppProperties;
import com.instanthelp.identity.AppUser;
import com.instanthelp.identity.AppUserRepository;
import com.instanthelp.identity.UserStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final TaskCategoryRepository categories;
    private final AppUserRepository users;
    private final AppProperties props;

    public TaskService(TaskRepository tasks, TaskEventRepository events, TaskCategoryRepository categories,
                       AppUserRepository users, AppProperties props) {
        this.tasks = tasks;
        this.events = events;
        this.categories = categories;
        this.users = users;
        this.props = props;
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
