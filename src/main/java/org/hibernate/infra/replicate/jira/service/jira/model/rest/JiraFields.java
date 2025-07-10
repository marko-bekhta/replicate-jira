package org.hibernate.infra.replicate.jira.service.jira.model.rest;

import java.time.ZonedDateTime;
import java.util.List;

import org.hibernate.infra.replicate.jira.service.jira.model.JiraBaseObject;

import com.fasterxml.jackson.annotation.JsonInclude;

public class JiraFields extends JiraBaseObject {

	public String summary;
	public String description;
	public JiraSimpleObject priority = new JiraSimpleObject();
	public JiraSimpleObject issuetype = new JiraSimpleObject();
	public JiraProject project = new JiraProject();
	public JiraSimpleObject status;
	public List<String> labels;

	public JiraUser assignee;
	public JiraUser reporter;
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public List<JiraVersion> fixVersions;
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public List<JiraVersion> versions; // this is actually `Affects versions`
	// NOTE: this one is for "read-only" purposes, to create links a different API
	// has to be used
	public List<JiraIssueLink> issuelinks;
	public JiraComments comment;
	public JiraIssue parent;
	public ZonedDateTime created;
	public ZonedDateTime updated;

	public static JiraFields empty() {
		JiraFields fields = new JiraFields();
		fields.priority = null;
		fields.issuetype = null;
		fields.project = null;
		return fields;
	}

	@Override
	public String toString() {
		return "JiraFields{" + "summary='" + summary + '\'' + ", description=" + description + ", priority=" + priority
				+ ", issuetype=" + issuetype + ", project=" + project + ", labels=" + labels + "otherProperties="
				+ properties() + '}';
	}

	public static String formatSummary(String summary) {
		// for some reason Jira server does not like ` in the summaries and can result
		// in very helpful "access denied" error responses
		return summary.replace("`", "");
	}

}
