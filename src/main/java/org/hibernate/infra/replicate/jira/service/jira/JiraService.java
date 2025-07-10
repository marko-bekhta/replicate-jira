package org.hibernate.infra.replicate.jira.service.jira;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.hibernate.infra.replicate.jira.JiraConfig;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestClient;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestClientBuilder;
import org.hibernate.infra.replicate.jira.service.jira.handler.JiraIssueDeleteEventHandler;
import org.hibernate.infra.replicate.jira.service.jira.handler.JiraIssueSimpleUpsertEventHandler;
import org.hibernate.infra.replicate.jira.service.jira.handler.JiraIssueTransitionOnlyEventHandler;
import org.hibernate.infra.replicate.jira.service.jira.model.action.JiraActionEvent;
import org.hibernate.infra.replicate.jira.service.jira.model.hook.JiraWebHookEvent;
import org.hibernate.infra.replicate.jira.service.jira.model.hook.JiraWebHookIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.hook.JiraWebHookIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.hook.JiraWebHookObject;
import org.hibernate.infra.replicate.jira.service.jira.model.hook.JiraWebhookEventType;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComment;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComments;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssues;
import org.hibernate.infra.replicate.jira.service.reporting.FailureCollector;
import org.hibernate.infra.replicate.jira.service.reporting.ReportingConfig;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.scheduler.Scheduler;
import io.quarkus.vertx.http.ManagementInterface;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

@ApplicationScoped
public class JiraService {

	private static final String SYSTEM_USER = "94KJcxFzgxZlXyTss4oR0rDNqtjwjhIiZLzYNx0Mwuc=";

	private final ReportingConfig reportingConfig;
	private final Map<String, HandlerProjectGroupContext> contextPerProjectGroup;
	private final JiraConfig jiraConfig;
	private final Scheduler scheduler;

	@Inject
	public JiraService(JiraConfig jiraConfig, ReportingConfig reportingConfig, Scheduler scheduler) {
		Map<String, HandlerProjectGroupContext> contextMap = new HashMap<>();
		for (var entry : jiraConfig.projectGroup().entrySet()) {
			JiraRestClient source = JiraRestClientBuilder.of(entry.getValue().source());
			JiraRestClient destination = JiraRestClientBuilder.of(entry.getValue().destination());
			HandlerProjectGroupContext groupContext = new HandlerProjectGroupContext(entry.getKey(), entry.getValue(),
					source, destination);
			contextMap.put(entry.getKey(), groupContext);
		}

		this.contextPerProjectGroup = Collections.unmodifiableMap(contextMap);
		this.reportingConfig = reportingConfig;

		configureScheduledTasks(scheduler, jiraConfig);
		this.jiraConfig = jiraConfig;
		this.scheduler = scheduler;
	}

	private void configureScheduledTasks(Scheduler scheduler, JiraConfig jiraConfig) {
		for (var entry : jiraConfig.projectGroup().entrySet()) {
			scheduler.newJob("Sync project group %s".formatted(entry.getKey()))
					.setCron(entry.getValue().scheduled().cron())
					.setConcurrentExecution(Scheduled.ConcurrentExecution.SKIP).setTask(executionContext -> {
						syncLastUpdated(entry.getKey());
					}).schedule();
		}
	}

	public void registerManagementRoutes(@Observes ManagementInterface mi) {
		mi.router().get("/sync/issues/init/:projectGroup/:project").blockingHandler(rc -> {
			// TODO: we can remove this one once we figure out why POST management does not
			// work correctly...
			String projectGroup = rc.pathParam("projectGroup");
			String project = rc.pathParam("project");
			List<String> maxToSyncList = rc.queryParam("maxToSync");
			AtomicInteger maxToSync = maxToSyncList.isEmpty()
					? null
					: new AtomicInteger(Integer.parseInt(maxToSyncList.get(0)) + 1);

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project group '%s'".formatted(projectGroup));
			}

			HandlerProjectContext projectContext = context.contextForOriginalProjectKey(project);

			AtomicLong largestSyncedJiraIssueKeyNumber = new AtomicLong(
					projectContext.getLargestSyncedJiraIssueKeyNumber());
			BooleanSupplier continueSyncing = maxToSync == null ? () -> true : () -> maxToSync.decrementAndGet() > 0;
			String identity = "Init Sync for project %s".formatted(project);
			scheduler.newJob(identity).setConcurrentExecution(Scheduled.ConcurrentExecution.SKIP)
					// every 10 seconds:
					.setCron("0/10 * * * * ?").setTask(executionContext -> {
						Optional<JiraIssue> issueToSync = projectContext
								.getNextIssueToSync(largestSyncedJiraIssueKeyNumber.get());
						if (issueToSync.isEmpty() || !continueSyncing.getAsBoolean()) {
							scheduler.unscheduleJob(identity);
						} else {
							triggerSyncEvent(issueToSync.get(), context);
							largestSyncedJiraIssueKeyNumber.set(JiraIssue.keyToLong(issueToSync.get().key));
						}
					}).schedule();
			rc.end();
		});
		mi.router().get("/sync/issues/re-sync/:projectGroup/:issue").blockingHandler(rc -> {
			// TODO: we can remove this one once we figure out why POST management does not
			// work correctly...
			String projectGroup = rc.pathParam("projectGroup");
			String issue = rc.pathParam("issue");

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			triggerSyncEvent(context.sourceJiraClient().getIssue(issue), context);
			rc.end();
		});
		mi.router().get("/sync/issues/deleted/:projectGroup").blockingHandler(rc -> {
			String projectGroup = rc.pathParam("projectGroup");
			String issues = rc.queryParam("issues").getFirst();

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			String[] split = issues.split(",");
			for (String key : split) {
				context.submitTask(new JiraIssueDeleteEventHandler(reportingConfig, context, -1L, key));
			}
			rc.end();
		});
		mi.router().get("/sync/issues/transition/re-sync/:projectGroup").blockingHandler(rc -> {
			// TODO: we can remove this one once we figure out why POST management does not
			// work correctly...
			String projectGroup = rc.pathParam("projectGroup");
			String query = rc.queryParam("query").getFirst();

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			context.submitTask(() -> {
				syncByQuery(query, context, jiraIssue -> context
						.submitTask(new JiraIssueTransitionOnlyEventHandler(reportingConfig, context, jiraIssue)));
			});
			rc.end();
		});
		mi.router().get("/sync/issues/query/full/:projectGroup").blockingHandler(rc -> {
			// syncs issue with comments, links etc.
			String projectGroup = rc.pathParam("projectGroup");
			String query = rc.queryParam("query").getFirst();

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			context.submitTask(() -> syncByQuery(query, context));
			rc.end();
		});
		mi.router().get("/sync/issues/query/simple/:projectGroup").blockingHandler(rc -> {
			// syncs only assignee/body, without links comments and transitions
			String projectGroup = rc.pathParam("projectGroup");
			String query = rc.queryParam("query").getFirst();
			boolean applyTransitionUpdate = "true".equalsIgnoreCase(rc.queryParam("applyTransitionUpdate").getFirst());

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			context.submitTask(() -> syncByQuery(query, context,
					jiraIssue -> context.submitTask(new JiraIssueSimpleUpsertEventHandler(reportingConfig, context,
							jiraIssue, applyTransitionUpdate))));
			rc.end();
		});
		mi.router().get("/sync/fix-versions/:projectGroup/:project").blockingHandler(rc -> {
			String projectGroup = rc.pathParam("projectGroup");
			String project = rc.pathParam("project");

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			context.submitTask(context.contextForOriginalProjectKey(project)::refreshFixVersions);
			rc.end();
		});
		mi.router().get("/sync/fix-versions/:projectGroup/:project/:versionId").blockingHandler(rc -> {
			String projectGroup = rc.pathParam("projectGroup");
			String project = rc.pathParam("project");
			String versionId = rc.pathParam("versionId");

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);

			if (context == null) {
				throw new IllegalArgumentException("Unknown project '%s'".formatted(projectGroup));
			}

			context.contextForOriginalProjectKey(project)
					.fixVersion(context.sourceJiraClient().version(Long.parseLong(versionId)), true);
			rc.end();
		});
	}

	/**
	 * At this point we want to only make some simple validation of the request
	 * body, and then add a request to the processing queue. We do not want to start
	 * doing actual processing before replying back to the Jira that we've received
	 * the event. It may be that processing will take some time, and it may result
	 * in a timeout on the hook side.
	 *
	 * @param projectGroup
	 *            The project group key as defined in the
	 *            {@link JiraConfig#projectGroup()}
	 * @param event
	 *            The body of the event posted by the webhook.
	 * @param triggeredByUser
	 *            The ID of the Jira user that triggered the webhook event.
	 */
	public void acknowledge(String projectGroup, JiraWebHookEvent event, String triggeredByUser) {
		event.eventType().ifPresentOrElse(eventType -> {
			var context = contextPerProjectGroup.get(projectGroup);
			if (context == null) {
				FailureCollector failureCollector = FailureCollector.collector(reportingConfig);
				failureCollector
						.critical("Unable to determine handler context for project group %s. Was it not configured ?"
								.formatted(projectGroup));
				failureCollector.close();
				throw new ConstraintViolationException("Project group" + projectGroup + " is not configured.",
						Set.of());
			}

			if (context.isUserIgnored(triggeredByUser)) {
				Log.infof("Event was triggered by %s user that is in the ignore list: %.200s", triggeredByUser, event);
				return;
			}

			for (Runnable handler : eventType.handlers(reportingConfig, event, context)) {
				context.submitTask(handler);
			}
		}, () -> Log.infof("Event type %s is not supported and cannot be handled.", event.webhookEvent));
	}

	public void downstreamAcknowledge(String project, JiraActionEvent event) {
		event.eventType().ifPresentOrElse(eventType -> {
			var context = contextPerProjectGroup.get(project);
			if (context == null) {
				FailureCollector failureCollector = FailureCollector.collector(reportingConfig);
				failureCollector.critical("Unable to determine handler context for project %s. Was it not configured ?"
						.formatted(project));
				failureCollector.close();
				throw new ConstraintViolationException("Project " + project + " is not configured.", Set.of());
			}

			if (context.isDownstreamUserIgnored(event.triggeredByUser)) {
				Log.infof("Event was triggered by %s user that is in the ignore list.", event.triggeredByUser);
				return;
			}

			for (Runnable handler : eventType.handlers(reportingConfig, event, context)) {
				context.submitDownstreamTask(handler);
			}
		}, () -> Log.infof("Event type %s is not supported and cannot be handled.", event.event));

	}

	public void syncLastUpdated(String projectGroup) {
		try (FailureCollector failureCollector = FailureCollector.collector(reportingConfig)) {
			Log.infof("Starting scheduled sync of issues for the project group %s", projectGroup);

			HandlerProjectGroupContext context = contextPerProjectGroup.get(projectGroup);
			JiraConfig.JiraProjectGroup group = jiraConfig.projectGroup().get(projectGroup);
			for (String project : group.projects().keySet()) {
				Log.infof("Generating issues for %s project.", project);

				HandlerProjectContext projectContext = context.contextForProject(project);
				String query = "project=%s and updated >= %s ORDER BY key".formatted(
						projectContext.project().originalProjectKey(), context.projectGroup().scheduled().timeFilter());
				try {
					syncByQuery(query, context);
				} catch (Exception e) {
					failureCollector
							.warning("Failed to fetch issues for a query '%s': %s".formatted(query, e.getMessage()), e);
					break;
				}
			}
		} finally {
			Log.info("Finished scheduled sync of issues");
		}
	}

	@PreDestroy
	public void finishProcessingAndShutdown() {
		for (HandlerProjectGroupContext context : contextPerProjectGroup.values()) {
			try {
				context.close();
			} catch (Exception e) {
				Log.errorf(e, "Error closing context %s: %s", context, e.getMessage());
			}
		}
	}

	private void syncByQuery(String query, HandlerProjectGroupContext context) {
		syncByQuery(query, context, jiraIssue -> triggerSyncEvent(jiraIssue, context));
	}

	private void syncByQuery(String query, HandlerProjectGroupContext context, Consumer<JiraIssue> action) {
		JiraIssues issues = null;
		String nextPageToken = null;
		int max = 100;
		do {
			issues = context.sourceJiraClient().find(query, nextPageToken, max, List.of("*all"));
			Log.infof("Sync by query \"%s\" will try syncing issues.",
					query.substring(0, Math.min(100, query.length())));
			issues.issues.forEach(action);

			nextPageToken = issues.nextPageToken;
		} while (!issues.isLast && nextPageToken != null);
	}

	private void triggerSyncEvent(JiraIssue jiraIssue, HandlerProjectGroupContext context) {
		Log.infof("Adding sync events for a jira issue: %s; Already queued events: %s", jiraIssue.key,
				context.pendingEventsInCurrentContext());

		JiraWebHookIssue issue = new JiraWebHookIssue();
		issue.id = jiraIssue.id;
		issue.key = jiraIssue.key;

		JiraWebHookEvent event = new JiraWebHookEvent();
		event.webhookEvent = JiraWebhookEventType.ISSUE_UPDATED.getName();
		event.issue = issue;

		acknowledge(context.projectGroupName(), event, SYSTEM_USER);

		// now sync comments:
		if (jiraIssue.fields.comment != null && jiraIssue.fields.comment.comments != null) {
			triggerCommentSyncEvents(context.projectGroupName(), issue, jiraIssue.fields.comment.comments);
		} else {
			// comments not always come in the jira request... so if we didn't get any, just
			// in case we will query for them:
			JiraComments comments = context.sourceJiraClient().getComments(jiraIssue.id, 0, 500);
			triggerCommentSyncEvents(context.projectGroupName(), issue, comments.comments);
		}

		// and links:
		if (jiraIssue.fields.issuelinks != null) {
			for (JiraIssueLink link : jiraIssue.fields.issuelinks) {
				event = new JiraWebHookEvent();
				event.webhookEvent = JiraWebhookEventType.ISSUELINK_CREATED.getName();
				event.issueLink = new JiraWebHookIssueLink();
				event.issueLink.id = Long.parseLong(link.id);

				acknowledge(context.projectGroupName(), event, SYSTEM_USER);
			}
		}
	}

	private void triggerCommentSyncEvents(String projectKey, JiraWebHookIssue issue, List<JiraComment> comments) {
		for (JiraComment comment : comments) {
			var event = new JiraWebHookEvent();
			event.comment = new JiraWebHookObject();
			event.comment.id = Long.parseLong(comment.id);
			event.issue = issue;
			event.webhookEvent = JiraWebhookEventType.COMMENT_UPDATED.getName();
			acknowledge(projectKey, event, SYSTEM_USER);
		}
	}
}
