package org.hibernate.infra.replicate.jira.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestClient;
import org.hibernate.infra.replicate.jira.service.jira.client.JiraRestException;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComment;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComments;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueBulk;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueBulkResponse;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLinkTypes;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueResponse;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueTransition;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssues;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraProject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraRemoteLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraSimpleObject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransition;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransitions;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraUser;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraVersion;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SampleJiraRestClient implements JiraRestClient {

	public final AtomicReference<String> itemCannotBeFound = new AtomicReference<>("");
	public final AtomicBoolean hasIssueLinks = new AtomicBoolean(true);
	private final AtomicLong currentIssueKey = new AtomicLong(0);

	@Inject
	ObjectMapper objectMapper;

	@Override
	public JiraIssue getIssue(String key) {
		if (Pattern.compile(itemCannotBeFound.get()).matcher(key).matches()) {
			throw new JiraRestException("No issue %s".formatted(key), 404, Map.of());
		}
		return sample(1L, key);
	}

	@Override
	public JiraIssue getIssue(Long id) {
		if (Pattern.compile(itemCannotBeFound.get()).matcher(Long.toString(id)).matches()) {
			throw new JiraRestException("No issue %s".formatted(id), 404, Map.of());
		}
		return sample(id, jiraKey(id));
	}

	@Override
	public JiraIssueResponse create(JiraIssue issue) {
		JiraIssueResponse response = new JiraIssueResponse();
		// we just return a high number as if a lot of issues were already created:
		response.key = jiraKey(100L);
		return response;
	}

	@Override
	public JiraIssueBulkResponse create(JiraIssueBulk bulk) {
		JiraIssueBulkResponse response = new JiraIssueBulkResponse();
		JiraIssueResponse e1 = new JiraIssueResponse();
		e1.key = jiraKey(currentIssueKey.addAndGet(bulk.issueUpdates.size()));
		response.issues = List.of(e1);

		return response;
	}

	@Override
	public JiraIssueResponse update(String key, JiraIssue issue) {
		JiraIssueResponse response = new JiraIssueResponse();
		response.key = key;
		return response;
	}

	@Override
	public void upsertRemoteLink(String key, JiraRemoteLink remoteLink) {
		// do nothing
	}

	@Override
	public JiraComment getComment(Long issueId, Long commentId) {
		if (Pattern.compile(itemCannotBeFound.get()).matcher("%d - %d".formatted(issueId, commentId)).matches()) {
			throw new JiraRestException("No comment %s".formatted(commentId), 404, Map.of());
		}
		return sampleComment(issueId, commentId);
	}

	@Override
	public JiraComment getComment(String issueKey, Long commentId) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public JiraComments getComments(Long issueId, int startAt, int maxResults) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public JiraComments getComments(String issueKey, int startAt, int maxResults) {
		JiraComments comments = new JiraComments();
		comments.comments = new ArrayList<>();
		comments.comments.add(
				sampleCopiedComment(Math.abs(Long.parseLong(issueKey.substring(issueKey.lastIndexOf('-')))), 123L));
		comments.maxResults = maxResults;
		comments.startAt = startAt;
		comments.total = 1;
		return comments;
	}

	@Override
	public JiraIssueResponse create(String issueKey, JiraComment comment) {
		JiraIssueResponse response = new JiraIssueResponse();
		response.key = issueKey;
		return response;
	}

	@Override
	public JiraIssueResponse update(String issueKey, String commentId, JiraComment comment) {
		JiraIssueResponse response = new JiraIssueResponse();
		response.key = issueKey;
		return response;
	}

	@Override
	public List<JiraSimpleObject> getPriorities() {
		return List.of();
	}

	@Override
	public List<JiraSimpleObject> getIssueTypes() {
		return List.of();
	}

	@Override
	public List<JiraSimpleObject> getStatues() {
		return List.of();
	}

	@Override
	public JiraIssueLinkTypes getIssueLinkTypes() {
		JiraIssueLinkTypes jiraIssueLinkTypes = new JiraIssueLinkTypes();
		jiraIssueLinkTypes.issueLinkTypes = List.of();
		return jiraIssueLinkTypes;
	}

	@Override
	public List<JiraUser> findUser(String email) {
		return List.of();
	}

	@Override
	public JiraIssueLink getIssueLink(Long id) {
		return sampleIssueLink(id);
	}

	@Override
	public void upsertIssueLink(JiraIssueLink link) {
		// do nothing
	}

	@Override
	public void deleteComment(String issueKey, String commentId) {
		// do nothing
	}

	@Override
	public void deleteIssueLink(String linkId) {
		// do nothing
	}

	@Override
	public JiraIssues find(String query, String nextPageToken, int maxResults, List<String> fields) {
		JiraIssues issues = new JiraIssues();
		issues.issues = List.of();
		return issues;
	}

	@Override
	public JiraIssues find(String query, int startAt, int maxResults) {
		JiraIssues issues = new JiraIssues();
		issues.issues = List.of();
		return issues;
	}

	@Override
	public void transition(String issueKey, JiraTransition transition) {
		// do nothing
	}

	@Override
	public JiraTransitions availableTransitions(String issueKey) {
		JiraTransitions transitions = new JiraTransitions();
		JiraIssueTransition transition = new JiraIssueTransition();
		transition.name = "To To Do";
		transition.id = "100";
		transition.to = new JiraSimpleObject("1234");
		transition.to.name = "To Do";
		transitions.transitions = List.of(transition);
		return transitions;
	}

	@Override
	public void archive(String issueKey) {
		// do nothing
	}

	@Override
	public JiraVersion version(Long id) {
		return new JiraVersion(Objects.toString(id, null));
	}

	@Override
	public List<JiraVersion> versions(String projectKey) {
		return List.of(new JiraVersion("version"));
	}

	@Override
	public JiraVersion create(JiraVersion version) {
		return version;
	}

	@Override
	public JiraVersion update(String id, JiraVersion version) {
		return version;
	}

	@Override
	public void assign(String id, JiraUser assignee) {
		// ok
	}

	@Override
	public JiraProject project(String projectId) {
		JiraProject project = new JiraProject();
		project.id = projectId;
		project.key = "JIRATEST1";
		return project;
	}

	private JiraIssueLink sampleIssueLink(Long id) {
		try {
			return objectMapper.readValue("""
					{
					"id": "%1$s",
					"self": "https://hibernate.atlassian.net/rest/api/2/issueLink/%1$s",
					"type": {
						"id": "10050",
						"name": "Cause",
						"inward": "caused by",
						"outward": "causes",
						"self": "https://hibernate.atlassian.net/rest/api/2/issueLinkType/10050"
					},
					"inwardIssue": {
						"id": "77203",
						"key": "JIRATEST1-1",
						"self": "https://hibernate.atlassian.net/rest/api/2/issue/77203"
					},
					"outwardIssue": {
						"id": "77237",
						"key": "JIRATEST1-7",
						"self": "https://hibernate.atlassian.net/rest/api/2/issue/77237"
					}
					}
					""".formatted(id), JiraIssueLink.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private JiraComment sampleComment(Long issueId, Long commentId) {
		try {
			return objectMapper.readValue(
					"""
							{
							"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/%2$s",
							"id": "%2$s",
							"author": {
								"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
								"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
							},
							"body": "this is a comment 😉   123",
							"updateAuthor": {
								"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
								"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
							},
							"created": "2024-09-16T01:03:48.558-0700",
							"updated": "2024-09-17T08:34:02.203-0700",
							"jsdPublic": true
							}
							"""
							.formatted(issueId, commentId),
					JiraComment.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private JiraComment sampleCopiedComment(Long issueId, Long commentId) {
		try {
			return objectMapper.readValue(
					"""
							{
							"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/%2$s",
							"id": "%2$s",
							"author": {
								"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=%1$s%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
								"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
							},
							"body": "{quote}This [comment|https://hibernate.atlassian.net/browse/JIRATEST1-%1$s?focusedCommentId=%2$s] was posted by the [user :18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5|https://hibernate.atlassian.net/jira/people/557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5].{quote}\\n\\n\\nthis is a comment 😉   123",
							"updateAuthor": {
								"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
								"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
							},
							"created": "2024-09-16T01:03:48.558-0700",
							"updated": "2024-09-17T08:34:02.203-0700",
							"jsdPublic": true
							}
							"""
							.formatted(issueId, commentId),
					JiraComment.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private JiraIssue sample(Long id, String key) {
		try {
			String projectKey = key.substring(0, key.lastIndexOf('-'));
			String issueLinks = hasIssueLinks.get() ? """
					{
					"id": "51285",
					"self": "https://hibernate.atlassian.net/rest/api/2/issueLink/51285",
					"type": {
						"id": "10100",
						"name": "Blocks",
						"inward": "is blocked by",
						"outward": "blocks",
						"self": "https://hibernate.atlassian.net/rest/api/2/issueLinkType/10100"
					},
					"inwardIssue": {
						"id": "2",
						"key": "%1$s-2",
						"self": "https://hibernate.atlassian.net/rest/api/2/issue/2"
					}
					},
					{
					"id": "51288",
					"self": "https://hibernate.atlassian.net/rest/api/2/issueLink/51288",
					"type": {
						"id": "10050",
						"name": "Cause",
						"inward": "caused by",
						"outward": "causes",
						"self": "https://hibernate.atlassian.net/rest/api/2/issueLinkType/10050"
					},
					"outwardIssue": {
						"id": "7",
						"key": "%1$s-7",
						"self": "https://hibernate.atlassian.net/rest/api/2/issue/77237"
					}
					}
					""".formatted(projectKey) : "";
			return objectMapper.readValue(
					"""
							{
								"id": "%1$s",
								"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s",
								"key": "%2$s",
								"fields": {
								"statuscategorychangedate": "2024-09-12T00:59:51.378-0700",
								"issuetype": {
									"self": "https://hibernate.atlassian.net/rest/api/2/issuetype/3",
									"id": "3",
									"description": "A task that needs to be done.",
									"name": "Task"
								},
								"timespent": null,
								"project": {
									"self": "https://hibernate.atlassian.net/rest/api/2/project/10322",
									"id": "10322",
									"key": "%3$s",
									"name": "Test project to test Jira features"
								},
								"fixVersions": [],
								"created": "2024-09-12T00:59:50.844-0700",
								"priority": {
									"self": "https://hibernate.atlassian.net/rest/api/2/priority/5",
									"name": "Trivial",
									"id": "5"
								},
								"labels": [
									"test"
								],
								"versions": [],
								"issuelinks": [ %4$s ],
								"assignee": {
									"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
									"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
								},
								"updated": "2024-09-17T08:51:23.997-0700",
								"status": {
									"self": "https://hibernate.atlassian.net/rest/api/2/status/10100",
									"description": "",
									"name": "To Do",
									"id": "10100"
								},
								"components": [],
								"description": "*some description   aa    aaaaaaa1*",
								"summary": "First test issue",
								"creator": {
									"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
									"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
								},
								"customfield_10080": null,
								"customfield_10081": null,
								"subtasks": [],
								"reporter": {
									"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
									"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
								},
								"aggregateprogress": {
									"progress": 0,
									"total": 0
								},
								"comment": {
									"comments": [
									{
										"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/116631",
										"id": "116631",
										"author": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"body": "this is a comment 😉   123",
										"updateAuthor": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"created": "2024-09-16T01:03:48.558-0700",
										"updated": "2024-09-17T08:34:02.203-0700",
										"jsdPublic": true
									},
									{
										"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/116651",
										"id": "116651",
										"author": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"body": "{quote}This is some quote{quote}\\n\\nSome other comment content goes here:\\n\\n{code:c}and some code goes here 123456789{code}\\n\\nh1. Then we have some title\\n\\n*-_And some more styled text_-* ",
										"updateAuthor": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"created": "2024-09-16T11:31:57.870-0700",
										"updated": "2024-09-17T00:58:37.022-0700",
										"jsdPublic": true
									},
									{
										"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/116657",
										"id": "116657",
										"author": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"body": "Some text",
										"updateAuthor": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"created": "2024-09-17T00:57:35.694-0700",
										"updated": "2024-09-17T00:57:35.694-0700",
										"jsdPublic": true
									},
									{
										"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment/116662",
										"id": "116662",
										"author": {
										"self": "https://hibernate.atlassian.net/rest/api/2/user?accountId=557058%%3A18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5",
										"accountId": "557058:18cf44bb-bc9b-4e8d-b1b7-882969ddc8e5"
										},
										"body": "some comment"
									}
									],
									"self": "https://hibernate.atlassian.net/rest/api/2/issue/%1$s/comment",
									"maxResults": 4,
									"total": 4,
									"startAt": 0
								}
								}
							}
							"""
							.formatted(id, key, projectKey, issueLinks),
					JiraIssue.class);
		} catch (JsonProcessingException e) {
			throw new RuntimeException(e);
		}
	}

	private static String jiraKey(Long id) {
		return "JIRATEST1-%d".formatted(id);
	}
}
