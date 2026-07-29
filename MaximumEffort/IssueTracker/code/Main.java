import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Jira-like Issue Tracker (Dynamic Workflow Engine) — ONE FILE
 * Patterns: Facade + graph workflow + Strategy guards + Observer + per-issue lock
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum IssueType {
    EPIC,
    STORY,
    BUG,
    TASK,
    SUBTASK
}

enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

class Status {
    private final String name;

    Status(String name) {
        this.name = name.toUpperCase();
    }

    String getName() { return name; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Status)) return false;
        return Objects.equals(name, ((Status) o).name);
    }

    @Override
    public int hashCode() { return Objects.hash(name); }

    @Override
    public String toString() { return name; }
}

class User {
    private final String userId;
    private final String name;

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    String getUserId() { return userId; }
    String getName() { return name; }
}

// ========== AUDIT ==========

class AuditLog {
    private final String logId;
    private final String issueId;
    private final String eventType; // TRANSITION | FIELD_UPDATE
    private final Status fromStatus;
    private final Status toStatus;
    private final String detail;
    private final String actorUserId;
    private final long timestamp;

    private AuditLog(String issueId, String eventType, Status fromStatus, Status toStatus,
                     String detail, String actorUserId) {
        this.logId = UUID.randomUUID().toString();
        this.issueId = issueId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.detail = detail;
        this.actorUserId = actorUserId;
        this.timestamp = System.currentTimeMillis();
    }

    static AuditLog transition(String issueId, Status from, Status to, String actorUserId) {
        return new AuditLog(issueId, "TRANSITION", from, to, from + " -> " + to, actorUserId);
    }

    static AuditLog fieldUpdate(String issueId, String detail, String actorUserId) {
        return new AuditLog(issueId, "FIELD_UPDATE", null, null, detail, actorUserId);
    }

    String getIssueId() { return issueId; }
    String getEventType() { return eventType; }
    Status getFromStatus() { return fromStatus; }
    Status getToStatus() { return toStatus; }
    String getDetail() { return detail; }
    String getActorUserId() { return actorUserId; }
}

// ========== ISSUE ==========

class Issue {
    private final String issueId;
    private final IssueType type;
    private String summary;
    private volatile Status status;
    private volatile User assignee;
    private final User reporter;
    private Priority priority;
    private final String parentIssueId;
    private final List<String> childIssueIds = new CopyOnWriteArrayList<>();
    private final Map<String, Object> customFields = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    Issue(String issueId, IssueType type, String summary, User reporter,
          Status initialStatus, String parentIssueId) {
        this.issueId = issueId;
        this.type = type;
        this.summary = summary;
        this.reporter = reporter;
        this.status = initialStatus;
        this.priority = Priority.MEDIUM;
        this.parentIssueId = parentIssueId;
    }

    String getIssueId() { return issueId; }
    IssueType getType() { return type; }
    String getSummary() { return summary; }
    Status getStatus() { return status; }
    User getAssignee() { return assignee; }
    User getReporter() { return reporter; }
    String getParentIssueId() { return parentIssueId; }
    List<String> getChildIssueIds() { return childIssueIds; }
    ReentrantLock getLock() { return lock; }
    Map<String, Object> getCustomFields() { return customFields; }

    void setStatus(Status status) { this.status = status; }
    void setAssignee(User assignee) { this.assignee = assignee; }
    void setCustomField(String key, Object value) { customFields.put(key, value); }
    void addChildIssue(String childId) { childIssueIds.add(childId); }
}

// ========== OBSERVER ==========

interface IssueEventListener {
    void onTransitioned(Issue issue, Status from, Status to, User actor);
}

// ========== WORKFLOW ENGINE ==========

@FunctionalInterface
interface TransitionGuard {
    boolean validate(Issue issue, User actor, Status targetStatus);
}

class Transition {
    private final String name;
    private final Status fromStatus;
    private final Status toStatus;
    private final List<TransitionGuard> guards = new ArrayList<>();

    Transition(String name, Status fromStatus, Status toStatus) {
        this.name = name;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
    }

    Transition addGuard(TransitionGuard guard) {
        guards.add(guard);
        return this;
    }

    String getName() { return name; }
    Status getFromStatus() { return fromStatus; }
    Status getToStatus() { return toStatus; }

    boolean canExecute(Issue issue, User actor) {
        for (TransitionGuard guard : guards) {
            if (!guard.validate(issue, actor, toStatus)) {
                return false;
            }
        }
        return true;
    }
}

class Workflow {
    private final String name;
    private final Status initialStatus;
    private final Map<Status, Map<Status, Transition>> transitionGraph = new HashMap<>();

    Workflow(String name, Status initialStatus) {
        this.name = name;
        this.initialStatus = initialStatus;
    }

    String getName() { return name; }
    Status getInitialStatus() { return initialStatus; }

    Workflow addTransition(Transition transition) {
        transitionGraph
                .computeIfAbsent(transition.getFromStatus(), k -> new HashMap<>())
                .put(transition.getToStatus(), transition);
        return this;
    }

    Transition getTransition(Status from, Status to) {
        Map<Status, Transition> available = transitionGraph.get(from);
        return available != null ? available.get(to) : null;
    }
}

class WorkflowRegistry {
    private final Map<IssueType, Workflow> typeWorkflows = new ConcurrentHashMap<>();

    void registerWorkflow(IssueType type, Workflow workflow) {
        typeWorkflows.put(type, workflow);
    }

    Workflow getWorkflow(IssueType type) {
        Workflow wf = typeWorkflows.get(type);
        if (wf == null) {
            throw new IllegalStateException("No workflow registered for IssueType: " + type);
        }
        return wf;
    }
}

// ========== ISSUE SERVICE (FACADE) ==========

class IssueService {
    private final Map<String, Issue> issueStore = new ConcurrentHashMap<>();
    private final List<AuditLog> auditLogs = new CopyOnWriteArrayList<>();
    private final List<IssueEventListener> listeners = new CopyOnWriteArrayList<>();
    private final WorkflowRegistry workflowRegistry;
    private final AtomicInteger issueSeq = new AtomicInteger(100);

    IssueService(WorkflowRegistry workflowRegistry) {
        this.workflowRegistry = workflowRegistry;
    }

    void addListener(IssueEventListener listener) {
        listeners.add(listener);
    }

    Issue createIssue(IssueType type, String summary, User reporter, String parentIssueId) {
        Workflow wf = workflowRegistry.getWorkflow(type);
        String issueId = type.name() + "-" + issueSeq.incrementAndGet();

        Issue issue = new Issue(issueId, type, summary, reporter, wf.getInitialStatus(), parentIssueId);
        issueStore.put(issueId, issue);

        if (parentIssueId != null) {
            Issue parent = issueStore.get(parentIssueId);
            if (parent != null) {
                parent.addChildIssue(issueId);
            }
        }

        System.out.println("[IssueService] Created " + issueId + " [" + type + "] status="
                + wf.getInitialStatus());
        return issue;
    }

    void assignIssue(String issueId, User assignee, User actor) {
        Issue issue = requireIssue(issueId);
        issue.getLock().lock();
        try {
            issue.setAssignee(assignee);
            auditLogs.add(AuditLog.fieldUpdate(issueId, "assignee=" + assignee.getName(), actor.getUserId()));
            System.out.println("[Field] " + issueId + " assignee -> " + assignee.getName());
        } finally {
            issue.getLock().unlock();
        }
    }

    void setCustomField(String issueId, String key, Object value, User actor) {
        Issue issue = requireIssue(issueId);
        issue.getLock().lock();
        try {
            issue.setCustomField(key, value);
            auditLogs.add(AuditLog.fieldUpdate(issueId, key + "=" + value, actor.getUserId()));
            System.out.println("[Field] " + issueId + " " + key + " -> " + value);
        } finally {
            issue.getLock().unlock();
        }
    }

    boolean transitionIssue(String issueId, Status targetStatus, User actor) {
        Issue issue = requireIssue(issueId);

        issue.getLock().lock();
        Status from;
        try {
            Workflow workflow = workflowRegistry.getWorkflow(issue.getType());
            from = issue.getStatus();

            Transition transition = workflow.getTransition(from, targetStatus);
            if (transition == null) {
                System.out.println("[Rejected] No edge " + from + " -> " + targetStatus + " for " + issueId);
                return false;
            }
            if (!transition.canExecute(issue, actor)) {
                System.out.println("[Guard Failed] " + actor.getName() + " cannot move "
                        + issueId + " -> " + targetStatus);
                return false;
            }

            issue.setStatus(targetStatus);
            auditLogs.add(AuditLog.transition(issueId, from, targetStatus, actor.getUserId()));
            System.out.println("[Transition] " + issueId + ": " + from + " -> " + targetStatus
                    + " by " + actor.getName());
        } finally {
            issue.getLock().unlock();
        }

        // Notify outside lock (avoid listener deadlocks)
        for (IssueEventListener listener : listeners) {
            listener.onTransitioned(issue, from, targetStatus, actor);
        }
        return true;
    }

    Issue getIssue(String issueId) { return issueStore.get(issueId); }

    List<AuditLog> getAuditLogs() {
        return Collections.unmodifiableList(auditLogs);
    }

    private Issue requireIssue(String issueId) {
        Issue issue = issueStore.get(issueId);
        if (issue == null) {
            throw new IllegalArgumentException("Issue not found: " + issueId);
        }
        return issue;
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        Status open = new Status("OPEN");
        Status inProgress = new Status("IN_PROGRESS");
        Status inReview = new Status("IN_REVIEW");
        Status closed = new Status("CLOSED");
        Status done = new Status("DONE");

        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");

        TransitionGuard mustHaveAssignee =
                (issue, actor, target) -> issue.getAssignee() != null;
        TransitionGuard mustHaveResolution =
                (issue, actor, target) -> issue.getCustomFields().containsKey("resolution");

        Workflow bugWorkflow = new Workflow("Bug Workflow", open);
        bugWorkflow
                .addTransition(new Transition("Start Work", open, inProgress).addGuard(mustHaveAssignee))
                .addTransition(new Transition("Submit Review", inProgress, inReview))
                .addTransition(new Transition("Close Bug", inReview, closed).addGuard(mustHaveResolution));

        // Second workflow — shows per-type config without changing Issue class
        Workflow storyWorkflow = new Workflow("Story Workflow", open);
        storyWorkflow
                .addTransition(new Transition("Start", open, inProgress).addGuard(mustHaveAssignee))
                .addTransition(new Transition("Finish", inProgress, done));

        WorkflowRegistry registry = new WorkflowRegistry();
        registry.registerWorkflow(IssueType.BUG, bugWorkflow);
        registry.registerWorkflow(IssueType.STORY, storyWorkflow);
        registry.registerWorkflow(IssueType.EPIC, storyWorkflow); // same simple graph for demo

        IssueService service = new IssueService(registry);
        service.addListener((issue, from, to, actor) ->
                System.out.println("[Observer] notify: " + issue.getIssueId()
                        + " " + from + "->" + to));

        System.out.println("=== TEST 1: GUARD FAIL (NO ASSIGNEE) ===");
        Issue bug1 = service.createIssue(IssueType.BUG, "NPE on Login", alice, null);
        service.transitionIssue(bug1.getIssueId(), inProgress, alice);

        System.out.println("\n=== TEST 2: BUG HAPPY PATH ===");
        service.assignIssue(bug1.getIssueId(), alice, alice);
        service.transitionIssue(bug1.getIssueId(), inProgress, alice);
        service.transitionIssue(bug1.getIssueId(), inReview, alice);

        System.out.println("\n=== TEST 3: GUARD FAIL (NO RESOLUTION) ===");
        service.transitionIssue(bug1.getIssueId(), closed, bob);

        System.out.println("\n=== TEST 4: CLOSE WITH RESOLUTION ===");
        service.setCustomField(bug1.getIssueId(), "resolution", "FIXED", bob);
        service.transitionIssue(bug1.getIssueId(), closed, bob);

        System.out.println("\n=== TEST 5: EPIC → STORY HIERARCHY ===");
        Issue epic = service.createIssue(IssueType.EPIC, "Checkout redesign", alice, null);
        Issue story = service.createIssue(IssueType.STORY, "Pay button", bob, epic.getIssueId());
        System.out.println("Parent " + epic.getIssueId() + " children=" + epic.getChildIssueIds());
        System.out.println("Child " + story.getIssueId() + " parent=" + story.getParentIssueId());

        System.out.println("\nAudit events: " + service.getAuditLogs().size());
    }
}
