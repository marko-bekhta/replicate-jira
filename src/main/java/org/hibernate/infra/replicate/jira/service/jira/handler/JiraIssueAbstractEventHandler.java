package org.hibernate.infra.replicate.jira.service.jira.handler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.hibernate.infra.replicate.jira.JiraConfig;
import org.hibernate.infra.replicate.jira.service.jira.HandlerProjectContext;
import org.hibernate.infra.replicate.jira.service.jira.HandlerProjectGroupContext;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestException;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraFields;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraRemoteLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraSimpleObject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransition;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransitions;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraUser;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraVersion;
import org.hibernate.infra.replicate.jira.service.reporting.ReportingConfig;

abstract class JiraIssueAbstractEventHandler extends JiraEventHandler {

	private static final Pattern FIX_VERSION_PATTERN = Pattern.compile("Fix_version:.++");

	public JiraIssueAbstractEventHandler(ReportingConfig reportingConfig, HandlerProjectGroupContext context, Long id) {
		super(reportingConfig, context, id);
	}

	protected void applyTransition(JiraIssue sourceIssue, String destinationKey) {
		JiraIssue destIssue = context.destinationJiraClient().getIssue(destinationKey);
		applyTransition(sourceIssue, destIssue, destinationKey);
	}

	protected void applyTransition(JiraIssue sourceIssue, JiraIssue destIssue, String destinationKey) {
		Set<String> statusesToIgnore = context.projectGroup().statuses().ignoreTransitionCondition()
				.getOrDefault(sourceIssue.fields.status.name.toLowerCase(Locale.ROOT), Set.of());
		if (statusesToIgnore.contains(destIssue.fields.status.name.toLowerCase(Locale.ROOT))) {
			// no need to apply the transition :)
			return;
		}
		prepareTransition(sourceIssue.fields.status, destIssue).ifPresent(
				jiraTransition -> context.destinationJiraClient().transition(destinationKey, jiraTransition));
	}

	protected void updateIssueBody(HandlerProjectContext projectContext, JiraIssue sourceIssue, String destinationKey) {
		JiraIssue destIssue = context.destinationJiraClient().getIssue(destinationKey);
		updateIssueBody(projectContext, sourceIssue, destIssue, destinationKey);
	}

	protected void updateIssueBody(HandlerProjectContext projectContext, JiraIssue sourceIssue, JiraIssue destIssue,
			String destinationKey) {
		JiraIssue issue = issueToCreate(projectContext, sourceIssue, destIssue);

		updateIssue(destinationKey, issue, sourceIssue, context.notMappedAssignee());
	}

	protected void updateIssue(String destinationKey, JiraIssue issue, JiraIssue sourceIssue, JiraUser assignee) {
		try {
			context.destinationJiraClient().update(destinationKey, issue);
		} catch (JiraRestException e) {
			if (issue.fields.assignee == null) {
				// if we failed with no assignee then there's no point in retrying ...
				throw e;
			}
			if (e.getMessage().contains("\"assignee\"")) {
				// let's try updating with the assignee passed to the method (it may be the
				// "notMappedAssignee")
				failureCollector.warning(
						"Unable to update issue %s with assignee %s, will try to update one more time with assignee %s."
								.formatted(sourceIssue.key, issue.fields.assignee, assignee),
						e);
				issue.fields.assignee = assignee;
				updateIssue(destinationKey, issue, sourceIssue, null);
			} else {
				throw e;
			}
		}
	}

	protected JiraRemoteLink remoteSelfLink(JiraIssue sourceIssue) {
		URI jiraLink = createJiraIssueUri(sourceIssue);

		JiraRemoteLink link = new JiraRemoteLink();
		// >> Setting this field enables the remote issue link details to be updated or
		// >> deleted using remote system and item details as the record identifier,
		// >> rather than using the record's Jira ID.
		//
		// And if the appid/names are available then we can make it also look as if it
		// is not a remote link:

		Optional<String> formattedId = JiraRemoteLink.createGlobalId(context.projectGroup().issueLinkTypes(),
				sourceIssue.key);
		link.globalId = formattedId.orElseGet(jiraLink::toString);

		link.relationship = "Upstream issue";
		link.object.title = sourceIssue.key;
		link.object.url = jiraLink;
		link.object.summary = "Link to an upstream JIRA issue, from which this one was cloned.";

		return link;
	}

	protected JiraIssue issueToCreate(HandlerProjectContext projectContext, JiraIssue sourceIssue,
			JiraIssue downstreamIssue) {
		JiraIssue destinationIssue = new JiraIssue();
		destinationIssue.fields = new JiraFields();

		destinationIssue.fields.summary = JiraFields.formatSummary(sourceIssue.fields.summary);
		destinationIssue.fields.description = sourceIssue.fields.description;
		destinationIssue.fields.description = "%s%s".formatted(prepareDescriptionQuote(sourceIssue),
				Objects.toString(sourceIssue.fields.description, ""));
		destinationIssue.fields.description = truncateContent(destinationIssue.fields.description);

		destinationIssue.fields.labels = prepareLabels(sourceIssue, downstreamIssue);

		// if we can map the priority - great we'll do that, if no: we'll keep it blank
		// and let Jira use its default instead:
		destinationIssue.fields.priority = sourceIssue.fields.priority != null
				? priority(sourceIssue.fields.priority.id).map(JiraSimpleObject::new).orElse(null)
				: null;

		destinationIssue.fields.project.id = projectContext.project().projectId();

		destinationIssue.fields.issuetype = issueType(sourceIssue.fields.issuetype.id).map(JiraSimpleObject::new)
				.orElse(null);

		// now let's handle the users. we will consider only a mapped subset of users
		// and for other's the defaults will be used.
		// also the description is going to include a section mentioning who created and
		// who the issue is assigned to...
		if (context.projectGroup().canSetReporter()) {
			destinationIssue.fields.reporter = user(sourceIssue.fields.reporter).map(this::toUser).orElse(null);
		}

		if (sourceIssue.fields.assignee != null) {
			destinationIssue.fields.assignee = user(sourceIssue.fields.assignee).map(this::toUser)
					.orElseGet(context::notMappedAssignee);
		} else {
			// Because not sending an assignee just does not update it:
			// See also
			// https://confluence.atlassian.com/jirakb/how-to-set-assignee-to-unassigned-via-rest-api-in-jira-744721880.html
			destinationIssue.fields.assignee = JiraUser.unassigned(context.projectGroup().users().mappedPropertyName());
		}

		if (!isSubTaskIssue(sourceIssue.fields.issuetype) && sourceIssue.fields.parent != null) {
			if (isEpicIssue(sourceIssue.fields.parent.fields.issuetype)) {
				JiraConfig.IssueTypeValueMapping issueTypes = context.projectGroup().issueTypes();
				issueTypes.epicLinkKeyCustomFieldName()
						.ifPresent(epicLinkCustomFieldName -> destinationIssue.fields.properties().put(
								epicLinkCustomFieldName,
								projectContext.toDestinationKey(sourceIssue.fields.parent.key)));
			}
		}
		if (isEpicIssue(sourceIssue.fields.issuetype)) {
			// Try to set an epic label ... obviously it is a custom field >_< ...
			JiraConfig.IssueTypeValueMapping issueTypes = context.projectGroup().issueTypes();
			Object sourceEpicLabel = issueTypes.epicLinkSourceLabelCustomFieldName()
					.map(sourceIssue.fields.properties()::get).orElse(sourceIssue.fields.summary);

			issueTypes.epicLinkDestinationLabelCustomFieldName()
					.ifPresent(epicLinkDestinationLabelCustomFieldName -> destinationIssue.fields.properties()
							.put(epicLinkDestinationLabelCustomFieldName, sourceEpicLabel));
		}

		if (sourceIssue.fields.fixVersions != null) {
			destinationIssue.fields.fixVersions = new ArrayList<>();
			for (JiraVersion version : sourceIssue.fields.fixVersions) {
				JiraVersion downstream = projectContext.fixVersion(version);
				if (downstream != null) {
					destinationIssue.fields.fixVersions.add(downstream);
				}
			}
		}

		if (sourceIssue.fields.versions != null) {
			destinationIssue.fields.versions = new ArrayList<>();
			for (JiraVersion version : sourceIssue.fields.versions) {
				JiraVersion downstream = projectContext.fixVersion(version);
				if (downstream != null) {
					destinationIssue.fields.versions.add(downstream);
				}
			}
		}

		return destinationIssue;
	}

	private List<String> prepareLabels(JiraIssue sourceIssue, JiraIssue downstreamIssue) {
		List<String> labelsToSet = new ArrayList<>();

		for (String label : sourceIssue.fields.labels) {
			labelsToSet.add(asUpstreamLabel(label));
		}

		// let's also add fix versions to the labels
		if (sourceIssue.fields.fixVersions != null) {
			for (JiraVersion fixVersion : sourceIssue.fields.fixVersions) {
				String fixVersionLabel = "Fix version:%s".formatted(fixVersion.name).replace(' ', '_');
				labelsToSet.add(fixVersionLabel);
			}
		}

		for (String label : downstreamIssue.fields.labels) {
			if (!(context.isSourceLabel(label) || isFixVersion(label))) {
				labelsToSet.add(label);
			}
		}

		return labelsToSet;
	}

	private boolean isFixVersion(String label) {
		return FIX_VERSION_PATTERN.matcher(label).matches();
	}

	private String asUpstreamLabel(String label) {
		return context.projectGroup().formatting().labelTemplate().formatted(label);
	}

	private JiraUser toUser(String value) {
		return new JiraUser(context.projectGroup().users().mappedPropertyName(), value);
	}

	protected Optional<JiraTransition> prepareTransition(JiraSimpleObject sourceStatus, JiraIssue destIssue) {
		String downstreamStatus = context.projectGroup().statuses().mapping()
				.get(sourceStatus.name.toLowerCase(Locale.ROOT));
		return prepareTransition(downstreamStatus, destIssue);
	}

	protected Optional<JiraTransition> prepareTransition(String downstreamStatus, JiraIssue destIssue) {
		return statusToTransition(destIssue.fields.status.name, downstreamStatus,
				() -> JiraTransitions.findRequiredTransitionId(context.destinationJiraClient(), failureCollector,
						downstreamStatus, destIssue))
				.map(JiraTransition::new);
	}

	protected Optional<JiraIssueLink> prepareParentLink(HandlerProjectContext projectContext, String destinationKey,
			JiraIssue sourceIssue) {
		// we only add a link for sub-tasks (the ones that have a corresponding types).
		// Issues assigned to an epic, can also have a parent.
		// but for those we won't add an extra link:
		if (sourceIssue.fields.parent != null && isSubTaskIssue(sourceIssue.fields.issuetype)) {
			String parent = projectContext.toDestinationKey(sourceIssue.fields.parent.key);
			// we don't really need it, but as usual we are making sure that the issue is
			// available downstream:
			projectContext.createNextPlaceholderBatch(parent);
			JiraIssueLink link = new JiraIssueLink();
			link.type.id = context.projectGroup().issueLinkTypes().parentLinkType();
			// "name": "Depend",
			// "inward": "is depended on by",
			// "outward": "depends on",
			//
			// TODO: Jira is sending a relates-to link created hook on its own
			// and it has the inward/outward sides opposite to what we do here
			// Let's double check what will happen with actual downstream jira
			// (there a depends on link should be created along side the one triggered
			// automatically by the source JIRA).
			link.inwardIssue.key = destinationKey;
			link.outwardIssue.key = parent;
			return Optional.of(link);
		} else {
			return Optional.empty();
		}
	}

	private boolean isSubTaskIssue(JiraSimpleObject issueType) {
		if (issueType == null) {
			return false;
		}
		return Boolean.parseBoolean(Objects.toString(issueType.properties().get("subtask"), null));
	}

	private boolean isEpicIssue(JiraSimpleObject issueType) {
		if (issueType == null) {
			return false;
		}
		return "epic".equalsIgnoreCase(issueType.name);
	}

	private String prepareDescriptionQuote(JiraIssue issue) {
		URI issueUri = createJiraIssueUri(issue);

		UserData assignee = userData(issue.self, issue.fields.assignee);
		UserData reporter = userData(issue.self, issue.fields.reporter);

		return """
				{quote}This issue is created as a copy of [%s|%s].

				Assigned to: %s.

				Reported by: %s.

				Upstream status: %s.

				Created: %s.

				Last updated: %s.{quote}


				""".formatted(issue.key, issueUri,
				assignee == null ? " Unassigned" : "[%s|%s]".formatted(assignee.name(), assignee.uri()),
				reporter == null ? " Unknown" : "[%s|%s]".formatted(reporter.name(), reporter.uri()),
				issue.fields.status != null ? issue.fields.status.name : "Unknown",
				context.formatTimestamp(issue.fields.created),
				context.formatTimestamp(issue.fields.updated != null ? issue.fields.updated : issue.fields.created));
	}

}
