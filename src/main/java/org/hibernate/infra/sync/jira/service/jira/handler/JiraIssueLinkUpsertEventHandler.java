package org.hibernate.infra.sync.jira.service.jira.handler;

import org.hibernate.infra.sync.jira.service.jira.HandlerProjectContext;
import org.hibernate.infra.sync.jira.service.jira.client.JiraRestException;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.sync.jira.service.reporting.ReportingConfig;

public class JiraIssueLinkUpsertEventHandler extends JiraEventHandler {

	public JiraIssueLinkUpsertEventHandler(ReportingConfig reportingConfig, HandlerProjectContext context, Long id) {
		super( reportingConfig, context, id );
	}

	@Override
	protected void doRun() {
		JiraIssueLink issueLink = null;
		try {
			issueLink = context.sourceJiraClient().getIssueLink( objectId );
		}
		catch (JiraRestException e) {
			failureCollector.critical( "Source issue link %d was not found through the REST API".formatted( objectId ), e );
			// no point in continuing anything
			return;
		}

		// make sure that both sides of the link exist:
		getDestinationIssue( issueLink.outwardIssue.key );
		JiraIssue issue = getDestinationIssue( issueLink.inwardIssue.key );

		if ( issue.fields.issuelinks != null ) {
			// do we already have this issue link or not ?
			for ( JiraIssueLink issuelink : issue.fields.issuelinks ) {
				if ( issuelink.outwardIssue.key.equals( issueLink.outwardIssue.key )
						&& issuelink.type.name.equals( issueLink.type.name ) ) {
					return;
				}
			}
		}

		JiraIssueLink toCreate = new JiraIssueLink();
		toCreate.type.id = linkType( issueLink.type.id ).orElse( null );
		toCreate.inwardIssue.key = issueLink.inwardIssue.key;
		toCreate.outwardIssue.key = issueLink.outwardIssue.key;
		context.destinationJiraClient().upsertIssueLink( toCreate );
	}
}
