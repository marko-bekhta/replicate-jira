package org.hibernate.infra.replicate.jira.service.jira.handler;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.hibernate.infra.replicate.jira.JiraConfig;
import org.hibernate.infra.replicate.jira.service.jira.HandlerProjectGroupContext;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComment;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraSimpleObject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTextContent;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraUser;
import org.hibernate.infra.replicate.jira.service.reporting.FailureCollector;
import org.hibernate.infra.replicate.jira.service.reporting.ReportingConfig;

import io.quarkus.logging.Log;
import jakarta.ws.rs.core.UriBuilder;

public abstract class JiraEventHandler implements Runnable {
	protected static final int MAX_CONTENT_SIZE = 32_766;

	protected final Long objectId;
	protected final FailureCollector failureCollector;
	protected final HandlerProjectGroupContext context;

	protected JiraEventHandler(ReportingConfig reportingConfig, HandlerProjectGroupContext context, Long id) {
		this.objectId = id;
		this.failureCollector = FailureCollector.collector(reportingConfig);
		this.context = context;
	}

	protected static URI createJiraIssueUri(JiraIssue sourceIssue) {
		// e.g. https://hibernate.atlassian.net/browse/JIRATEST1-1
		return UriBuilder.fromUri(sourceIssue.self).replacePath("browse").path(sourceIssue.key).build();
	}

	protected static URI createJiraCommentUri(JiraIssue issue, JiraComment comment) {
		// e.g.
		// https://hibernate.atlassian.net/browse/JIRATEST1-1?focusedCommentId=116651
		return UriBuilder.fromUri(issue.self).replacePath("browse").path(issue.key).replaceQuery("")
				.queryParam("focusedCommentId", comment.id).build();
	}

	protected static URI createJiraUserUri(URI someJiraUri, JiraUser user) {
		// e.g.
		// https://hibernate.atlassian.net/jira/people/557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5
		if (user == null) {
			return null;
		}
		return UriBuilder.fromUri(someJiraUri).replacePath("jira").path("people").path(user.accountId).build();
	}

	protected Optional<String> priority(String sourceId) {
		JiraConfig.ValueMapping mappedValues = context.projectGroup().priorities();
		return Optional.ofNullable(JiraStaticFieldMappingCache.priority(context.projectGroupName(), sourceId, pk -> {
			Map<String, String> mapping = mappedValues.mapping();
			if (!mapping.isEmpty()) {
				return mapping;
			}

			// Otherwise we'll try to use REST to get the info and match, but that may not
			// necessarily work fine
			List<JiraSimpleObject> source = context.sourceJiraClient().getPriorities();
			List<JiraSimpleObject> destination = context.destinationJiraClient().getPriorities();

			return createMapping(source, destination);
		}, mappedValues.defaultValue()));
	}

	protected Optional<String> issueType(String sourceId) {
		JiraConfig.ValueMapping mappedValues = context.projectGroup().issueTypes();
		return Optional.ofNullable(JiraStaticFieldMappingCache.issueType(context.projectGroupName(), sourceId, pk -> {

			Map<String, String> mapping = context.projectGroup().issueTypes().mapping();
			if (!mapping.isEmpty()) {
				return mapping;
			}

			// Otherwise we'll try to use REST to get the info and match, but that may not
			// necessarily work fine
			List<JiraSimpleObject> source = context.sourceJiraClient().getIssueTypes();
			List<JiraSimpleObject> destination = context.destinationJiraClient().getIssueTypes();

			return createMapping(source, destination);

		}, mappedValues.defaultValue()));
	}

	protected Optional<String> statusToTransition(String from, String to, Supplier<Optional<String>> transitionFinder) {
		return Optional.ofNullable(JiraStaticFieldMappingCache.status(context.projectGroupName(),
				"%s->%s".formatted(from, to), tk -> transitionFinder.get().orElse(null)));
	}

	protected Optional<String> linkType(String sourceId) {
		JiraConfig.ValueMapping mappedValues = context.projectGroup().issueLinkTypes();
		return Optional.ofNullable(JiraStaticFieldMappingCache.linkType(context.projectGroupName(), sourceId, pk -> {
			Map<String, String> mapping = context.projectGroup().issueLinkTypes().mapping();
			if (!mapping.isEmpty()) {
				return mapping;
			}

			// Otherwise we'll try to use REST to get the info and match, but that may not
			// necessarily work fine
			List<JiraSimpleObject> source = context.sourceJiraClient().getIssueLinkTypes().issueLinkTypes;
			List<JiraSimpleObject> destination = context.destinationJiraClient().getIssueLinkTypes().issueLinkTypes;

			return createMapping(source, destination);
		}, mappedValues.defaultValue()));
	}

	protected Optional<String> user(JiraUser sourceUser) {
		if (sourceUser == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(JiraStaticFieldMappingCache.user(context.projectGroupName(), sourceUser.accountId,
				userId -> context.projectGroup().users().mapping().get(sourceUser.accountId)));
	}

	protected UserData userData(URI someUpstreamUri, JiraUser user) {
		return userData(someUpstreamUri, user, "user %s");
	}

	protected UserData userData(URI someUpstreamUri, JiraUser user, String unmappedUserPattern) {
		if (user == null) {
			return null;
		}
		Optional<String> mappedUser = user(user);
		URI jiraUserUri;
		String userName;
		if (mappedUser.isPresent()) {
			// means it is one of the users that we've mapped so we would want to point it
			// to the user on the "downstream" side and also add a name:
			Optional<String> template = context.projectGroup().users().profileUrl();
			if (template.isPresent()) {
				jiraUserUri = UriBuilder.fromUri(template.get()).build(mappedUser.get());
			} else {
				jiraUserUri = createJiraUserUri(someUpstreamUri, user);
			}
			// NOTE: we are using the upstream username here, so that we do not make an
			// extra call to get the downstream name. We probably can do that once at the
			// start and cache it, but for now this should be a "good-enough-approximation".
			userName = user.displayName;

		} else {
			jiraUserUri = createJiraUserUri(someUpstreamUri, user);
			userName = unmappedUserPattern.formatted(JiraTextContent.userIdPart(user));
		}
		return new UserData(userName, jiraUserUri);
	}

	private static Map<String, String> createMapping(List<JiraSimpleObject> source,
			List<JiraSimpleObject> destination) {
		Map<String, String> mapping = new HashMap<>();
		for (JiraSimpleObject p : source) {
			for (JiraSimpleObject d : destination) {
				if (p.name.equals(d.name)) {
					mapping.put(p.id, d.id);
					break;
				}
			}
		}
		return mapping;
	}

	@Override
	public final void run() {
		try {
			context.startProcessingEvent();
			doRun();
		} catch (RuntimeException e) {
			failureCollector.critical("Failed to handle the event: %s".formatted(this), e);
		} catch (InterruptedException e) {
			failureCollector.critical("Interrupted while waiting in the queue", e);
			Thread.currentThread().interrupt();
		} finally {
			failureCollector.close();
			Log.infof("Finished processing %s. Pending events in %s to process: %s", this.toString(),
					context.projectGroupName(), context.pendingEventsInCurrentContext());
		}
	}

	protected abstract void doRun();

	protected String truncateContent(String content) {
		// NOTE: description/comment content has a limit, and maybe the original one is
		// close to that limit but since we modify it with the quote info we better make
		// sure we are still able to fit the content and truncate anything at the end
		// (we have a link to the original if we want to read that very last part).
		//
		// A possible exception returned by the API:
		// {"errorMessages":[],"errors":{"description":"The entered text is too long. It
		// exceeds the allowed limit of 65,535 characters."}}
		if (content.length() > MAX_CONTENT_SIZE) {
			content = content.substring(0, MAX_CONTENT_SIZE);
		}
		return content;
	}

	protected String toProjectFromKey(String key) {
		int index = key.lastIndexOf('-');
		return index > 0 ? key.substring(0, index) : null;
	}

	public abstract String toString();

	protected record UserData(String name, URI uri) {
	}
}
