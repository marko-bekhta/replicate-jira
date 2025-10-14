package org.hibernate.infra.replicate.jira.service.jira.handler;

import java.net.URI;
import java.util.Optional;

import org.hibernate.infra.replicate.jira.service.jira.HandlerProjectContext;
import org.hibernate.infra.replicate.jira.service.jira.HandlerProjectGroupContext;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestException;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraRemoteLink;
import org.hibernate.infra.replicate.jira.service.reporting.ReportingConfig;

import jakarta.ws.rs.core.UriBuilder;

public class JiraIssueLinkUpsertEventHandler extends JiraEventHandler {

	public JiraIssueLinkUpsertEventHandler(ReportingConfig reportingConfig, HandlerProjectGroupContext context,
			Long id) {
		super(reportingConfig, context, id);
	}

	@Override
	protected void doRun() {
		JiraIssueLink sourceLink = null;
		try {
			sourceLink = context.sourceJiraClient().getIssueLink(objectId);
		} catch (JiraRestException e) {
			failureCollector.critical("Source issue link %d was not found through the REST API".formatted(objectId), e);
			// no point in continuing anything
			return;
		}

		Optional<HandlerProjectContext> outwardContextHolder = context
				.findContextForOriginalProjectKey(toProjectFromKey(sourceLink.outwardIssue.key));
		Optional<HandlerProjectContext> inwardContextHolder = context
				.findContextForOriginalProjectKey(toProjectFromKey(sourceLink.inwardIssue.key));

		if (inwardContextHolder.isPresent() && outwardContextHolder.isPresent()) {
			// means we want to create a simple issue between two projects in the same
			// project group
			// so it'll be a regular issue link:

			HandlerProjectContext inwardContext = inwardContextHolder.get();
			HandlerProjectContext outwardContext = outwardContextHolder.get();

			// make sure that both sides of the link exist:
			String outwardIssue = outwardContext.toDestinationKey(sourceLink.outwardIssue.key);
			String inwardIssue = inwardContext.toDestinationKey(sourceLink.inwardIssue.key);
			inwardContext.createNextPlaceholderBatch(outwardIssue);
			outwardContext.createNextPlaceholderBatch(inwardIssue);
			JiraIssue issue = context.destinationJiraClient().getIssue(inwardIssue);

			if (issue.fields.issuelinks != null) {
				// do we already have this issue link or not ?
				for (JiraIssueLink issuelink : issue.fields.issuelinks) {
					if ((outwardIssue.equals(issuelink.outwardIssue.key)
							|| inwardIssue.equals(issuelink.inwardIssue.key))
							&& issuelink.type.name.equals(sourceLink.type.name)) {
						return;
					}
				}
			}

			JiraIssueLink toCreate = new JiraIssueLink();
			toCreate.type.id = linkType(sourceLink.type.id).orElse(null);
			toCreate.inwardIssue.key = inwardIssue;
			toCreate.outwardIssue.key = outwardIssue;
			context.destinationJiraClient().upsertIssueLink(toCreate);
		} else if (outwardContextHolder.isPresent()) {
			createAsRemoteLink(sourceLink, sourceLink.inwardIssue.key, sourceLink.inwardIssue.id,
					outwardContextHolder.get().toDestinationKey(sourceLink.outwardIssue.key));
		} else if (inwardContextHolder.isPresent()) {
			createAsRemoteLink(sourceLink, sourceLink.outwardIssue.key, sourceLink.outwardIssue.id,
					inwardContextHolder.get().toDestinationKey(sourceLink.inwardIssue.key));
		} else {
			failureCollector.warning("Couldn't find a suitable way to process the issue link for %s".formatted(this));
		}
	}

	private void createAsRemoteLink(JiraIssueLink sourceLink, String linkedIssueKey, String linkedIssueId,
			String currentIssue) {
		URI jiraLink = UriBuilder.fromUri(sourceLink.self).replacePath("browse").path(linkedIssueKey).build();
		JiraRemoteLink link = new JiraRemoteLink();

		Optional<String> appId = context.projectGroup().issueLinkTypes().applicationIdForRemoteLinkType();
		link.globalId = appId.map(s -> "appId=%s&issueId=%s".formatted(s, linkedIssueId))
				.orElseGet(() -> sourceLink.self.toString());
		Optional<String> formattedId = JiraRemoteLink.createGlobalId(context.projectGroup().issueLinkTypes(),
				linkedIssueKey);
		link.globalId = formattedId.orElseGet(jiraLink::toString);

		link.relationship = sourceLink.type.name;
		link.object.title = linkedIssueKey;
		link.object.url = jiraLink;
	}

	@Override
	public String toString() {
		return "JiraIssueLinkUpsertEventHandler[" + "objectId=" + objectId + ", projectGroup="
				+ context.projectGroupName() + ']';
	}
}
