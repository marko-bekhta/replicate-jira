package org.hibernate.infra.sync.jira.service.jira.handler;

import java.net.URI;
import java.util.Optional;

import org.hibernate.infra.sync.jira.service.jira.HandlerProjectContext;
import org.hibernate.infra.sync.jira.service.jira.client.JiraRestException;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraComment;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraComments;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.sync.jira.service.jira.model.rest.JiraTextContent;
import org.hibernate.infra.sync.jira.service.reporting.ReportingConfig;

public class JiraCommentUpsertEventHandler extends JiraCommentEventHandler {

	public JiraCommentUpsertEventHandler(ReportingConfig reportingConfig, HandlerProjectContext context, Long commentId, Long issueId) {
		super( reportingConfig, context, commentId, issueId );
	}

	@Override
	protected void doRun() {
		JiraIssue issue = context.sourceJiraClient().getIssue( issueId );
		JiraComment comment = context.sourceJiraClient().getComment( issueId, objectId );

		// We are going to assume that the Jira issue was already synced downstream,
		//  and try to find its comments. If the issue is not yet there, then we'll just fail here:
		JiraComments destinationComments = null;
		try {
			destinationComments = context.destinationJiraClient().getComments( issue.key, 0, MAX_COMMENTS_RESULTS );
		}
		catch (JiraRestException e) {
			failureCollector.critical( "Failed to find an issue " + issue.key + " in the destination Jira. Unable to sync the comments.", e );
			return;
		}

		Optional<JiraComment> destComment = findComment( comment, destinationComments );
		if ( destComment.isPresent() ) {
			String commentId = destComment.get().id;
			context.destinationJiraClient().update( issue.key, commentId, prepareComment( issue, comment ) );
		}
		else {
			context.destinationJiraClient().create( issue.key, prepareComment( issue, comment ) );
		}
	}

	private JiraComment prepareComment(JiraIssue issue, JiraComment source) {
		JiraComment comment = new JiraComment();
		// we add the quote as a first element, and then follow it up with the original comment content:
		comment.body = prepareCommentQuote( issue, source ) + source.body;

		return comment;
	}

	private String prepareCommentQuote(JiraIssue issue, JiraComment comment) {
		URI jiraCommentUri = createJiraCommentUri( issue, comment );
		URI jiraUserUri = createJiraUserUri( comment.self, comment.author );
		return """
				{quote}This [comment|%s] was posted by the [user %s|%s].{quote}
				
				
				""".formatted( jiraCommentUri, JiraTextContent.userIdPart( comment.author ), jiraUserUri );
	}
}