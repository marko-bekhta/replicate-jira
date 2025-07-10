package org.hibernate.infra.replicate.jira.export;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.infra.replicate.jira.JiraConfig;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestClient;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestClientBuilder;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssues;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraVersion;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

@TestProfile(ExportProjectTest.Profile.class)
@QuarkusTest
@Disabled
class ExportProjectTest {

	private static final String DEFAULT_REPORTER_NAME = "hibernate-admins@redhat.com";
	private static final String DEFAULT_REPORTER_DISPLAY_NAME = "Hibernate Admins";

	public static class Profile implements QuarkusTestProfile {
		@Override
		public Map<String, String> getConfigOverrides() {
			return Map.of("jira.project-group.\"hibernate\".source.api-uri",
					"https://hibernate.atlassian.net/rest/api/2");
		}
	}

	// Set the PROJECT_ID to a project to export
	// 10060 - HV
	// Can either be an id or a project key:
	// If not all issues have to be exported update the `query` to find only the
	// ones that are required.
	private static final String PROJECT_ID = "HV";

	private static final int MAX_LABELS_LIMIT = 10;
	// in the service we map status id to a transition, but with the export we need
	// to have name-to-name mapping instead:
	private static final Map<String, String> statusMap;
	private static final Map<String, String> issueTypeMap;

	static {
		statusMap = new HashMap<>();
		statusMap.put("Awaiting Test Case", "New");
		statusMap.put("Awaiting Response", "New");
		statusMap.put("Waiting for review", "New");
		statusMap.put("Awaiting Contribution", "New");
		statusMap.put("Reopened", "New");
		statusMap.put("To Do", "New");
		statusMap.put("In Progress", "In Progress");
		statusMap.put("Stalled", "In Progress");
		statusMap.put("In Review", "In Progress");
		statusMap.put("Review", "In Progress");
		statusMap.put("Resolved", "Closed");
		statusMap.put("Closed", "Closed");
		statusMap.put("Done", "Closed");

		issueTypeMap = new HashMap<>();

		issueTypeMap.put("Bug", "Bug");
		issueTypeMap.put("New Feature", "Story");
		issueTypeMap.put("Task", "Task");
		issueTypeMap.put("Improvement", "Story");
		issueTypeMap.put("Patch", "Task");
		issueTypeMap.put("Deprecation", "Task");
		issueTypeMap.put("Sub-task", "Sub-task");
		issueTypeMap.put("Subtask", "Sub-task");
		issueTypeMap.put("Remove Feature", "Task");
		issueTypeMap.put("Technical task", "Task");
		issueTypeMap.put("Epic", "Epic");
		issueTypeMap.put("Story", "Story");
		issueTypeMap.put("Proposal", "Task");
	}

	@Inject
	JiraConfig jiraConfig;

	@Disabled
	@Test
	void export() throws IOException {
		JiraConfig.JiraProjectGroup projectGroup = jiraConfig.projectGroup().get("hibernate");
		JiraRestClient source = JiraRestClientBuilder.of(projectGroup.source());

		List<String> headers = new ArrayList<>(
				List.of("key", "issue type", "status", "project key", "project name", "reporter name",
						"reporter display name", "assignee name", "assignee display name", "summary", "description"));

		for (int i = 0; i < MAX_LABELS_LIMIT; i++) {
			headers.add("labels");
		}

		CSVFormat csvFormat = CSVFormat.DEFAULT.builder().setHeader(headers.toArray(String[]::new)).build();

		String nextPageToken = null;
		int max = 100; // this seems to be a limit on the jira side.. requesting more didn't work.

		JiraIssues issues;

		String query = "project=%s ORDER BY key".formatted(PROJECT_ID);

		try (final FileWriter fw = new FileWriter(
				"target/jira-exported-project-%s-%s.csv".formatted(PROJECT_ID, LocalDate.now()));
				final CSVPrinter printer = new CSVPrinter(fw, csvFormat)) {
			do {
				issues = source.find(query, nextPageToken, max, List.of("*all"));
				for (JiraIssue issue : issues.issues) {
					List<Object> row = new ArrayList<>();
					row.add(issue.key);
					row.add(issueTypeMap.getOrDefault(issue.fields.issuetype.name, ""));
					row.add(statusMap.getOrDefault(issue.fields.status.name, ""));
					row.add(issue.fields.project.properties().get("key"));
					row.add(issue.fields.project.properties().get("name"));
					Object reporter = issue.fields.reporter == null
							? null
							: projectGroup.users().mapping().getOrDefault(issue.fields.reporter.accountId, null);
					row.add(reporter == null ? DEFAULT_REPORTER_NAME : reporter);
					row.add(reporter != null ? issue.fields.reporter.displayName : DEFAULT_REPORTER_DISPLAY_NAME);
					Object assignee = issue.fields.assignee == null
							? null
							: projectGroup.users().mapping().getOrDefault(issue.fields.assignee.accountId, null);
					row.add(assignee);
					row.add(assignee != null ? issue.fields.assignee.displayName : null);
					row.add(issue.fields.summary);
					row.add(issue.fields.description);
					row.add(issue.key);
					row.addAll(formatLabels(issue));

					printer.printRecord(row);
				}
				nextPageToken = issues.nextPageToken;
			} while (!issues.isLast && nextPageToken != null);
		}
	}

	private List<String> formatLabels(JiraIssue issue) {
		List<String> labels = new ArrayList<>();
		if (issue.fields.labels != null && !issue.fields.labels.isEmpty()) {
			labels.addAll(issue.fields.labels);
		}

		if (issue.fields.fixVersions != null) {
			for (JiraVersion fixVersion : issue.fields.fixVersions) {
				labels.add("Fix version: %s".formatted(fixVersion.name).replace(' ', '_'));
			}
		}

		for (int i = labels.size(); i < MAX_LABELS_LIMIT; i++) {
			labels.add("");
		}

		return labels.subList(0, MAX_LABELS_LIMIT);
	}
}
