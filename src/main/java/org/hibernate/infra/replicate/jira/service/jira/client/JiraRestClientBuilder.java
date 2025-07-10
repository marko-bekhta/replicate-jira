package org.hibernate.infra.replicate.jira.service.jira.client;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.hibernate.infra.replicate.jira.JiraConfig;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComment;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraComments;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssue;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueBulk;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueBulkResponse;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueLinkTypes;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssueResponse;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraIssues;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraProject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraRemoteLink;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraSimpleObject;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransition;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraTransitions;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraUser;
import org.hibernate.infra.replicate.jira.service.jira.model.rest.JiraVersion;

import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.client.api.ClientLogger;
import org.jboss.resteasy.reactive.client.api.LoggingScope;

import io.quarkus.logging.Log;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import jakarta.ws.rs.core.Response;

public class JiraRestClientBuilder {

	public static JiraRestClient of(JiraConfig.Instance jira) {
		JiraConfig.JiraUser jiraUser = jira.apiUser();

		Map<String, String> headers = jira.loginKind().headers(jiraUser.email(), jiraUser.token());

		QuarkusRestClientBuilder builder = QuarkusRestClientBuilder.newBuilder().baseUri(jira.apiUri())
				.connectTimeout(5, TimeUnit.MINUTES).readTimeout(10, TimeUnit.MINUTES)
				.clientHeadersFactory((incomingHeaders, clientOutgoingHeaders) -> {
					for (var entry : headers.entrySet()) {
						clientOutgoingHeaders.add(entry.getKey(), entry.getValue());
					}
					return clientOutgoingHeaders;
				});
		if (jira.logRequests()) {
			builder.clientLogger(CustomClientLogger.INSTANCE).loggingScope(LoggingScope.REQUEST_RESPONSE);
		}
		return new JiraRestClientWithRetry(builder.build(JiraRestClient.class));
	}

	private static class CustomClientLogger implements ClientLogger {
		private static final CustomClientLogger INSTANCE = new CustomClientLogger();

		@Override
		public void setBodySize(int bodySize) {
			// ignored
		}

		@Override
		public void logResponse(HttpClientResponse response, boolean redirect) {
			response.bodyHandler(body -> Log.infof("%s: %s %s, Status[%d %s], Headers[%s], Body:\n%s",
					redirect ? "Redirect" : "Response", response.request().getMethod(),
					response.request().absoluteURI(), response.statusCode(), response.statusMessage(),
					asString(response.headers()), bodyToString(body)));
		}

		@Override
		public void logRequest(HttpClientRequest request, Buffer body, boolean omitBody) {
			if (omitBody) {
				Log.infof("Request: %s %s Headers[%s], Body omitted", request.getMethod(), request.absoluteURI(),
						asString(request.headers()));
			} else if (body == null || body.length() == 0) {
				Log.infof("Request: %s %s Headers[%s], Empty body", request.getMethod(), request.absoluteURI(),
						asString(request.headers()));
			} else {
				Log.infof("Request: %s %s Headers[%s], Body:\n%s", request.getMethod(), request.absoluteURI(),
						asString(request.headers()), bodyToString(body));
			}
		}

		private String bodyToString(Buffer body) {
			if (body == null) {
				return "";
			} else {
				return body.toString();
			}
		}

		private String asString(MultiMap headers) {
			if (headers.isEmpty()) {
				return "";
			}
			StringBuilder sb = new StringBuilder();
			boolean isFirst = true;
			for (Map.Entry<String, String> entry : headers) {
				if (isFirst) {
					isFirst = false;
				} else {
					sb.append(' ');
				}
				sb.append(entry.getKey()).append('=').append(entry.getValue());
			}
			return headers.entries().stream()
					.map(entry -> "%s: %s".formatted(entry.getKey(), sanitize(entry.getKey(), entry.getValue())))
					.collect(Collectors.joining(" | "));
		}

		private String sanitize(String header, String value) {
			if ("Authorization".equalsIgnoreCase(header)) {
				return "***";
			} else {
				return value;
			}
		}
	}

	// TODO: remove it once we figure out how to correctly integrate smallrye
	// fault-tolerance
	// (simply adding annotations on the REST interface does not work)
	private static class JiraRestClientWithRetry implements JiraRestClient {

		private final JiraRestClient delegate;

		private JiraRestClientWithRetry(JiraRestClient delegate) {
			this.delegate = delegate;
		}

		@Override
		public JiraIssue getIssue(String key) {
			return withRetry(() -> delegate.getIssue(key));
		}

		@Override
		public JiraIssue getIssue(Long id) {
			return delegate.getIssue(id);
		}

		@Override
		public JiraIssueResponse create(JiraIssue issue) {
			return withRetry(() -> delegate.create(issue));
		}

		@Override
		public JiraIssueBulkResponse create(JiraIssueBulk bulk) {
			return withRetry(() -> delegate.create(bulk));
		}

		@Override
		public JiraIssueResponse update(String key, JiraIssue issue) {
			// it might be that mapped user is wrong so we don't want to keep sending it
			return withRetry(() -> delegate.update(key, issue), 2);
		}

		@Override
		public void upsertRemoteLink(String key, JiraRemoteLink remoteLink) {
			withRetry(() -> delegate.upsertRemoteLink(key, remoteLink));
		}

		@Override
		public JiraComment getComment(Long issueId, Long commentId) {
			return withRetry(() -> delegate.getComment(issueId, commentId));
		}

		@Override
		public JiraComment getComment(String issueKey, Long commentId) {
			return withRetry(() -> delegate.getComment(issueKey, commentId));
		}

		@Override
		public JiraComments getComments(Long issueId, int startAt, int maxResults) {
			return withRetry(() -> delegate.getComments(issueId, startAt, maxResults));
		}

		@Override
		public JiraComments getComments(String issueKey, int startAt, int maxResults) {
			return withRetry(() -> delegate.getComments(issueKey, startAt, maxResults));
		}

		@Override
		public JiraIssueResponse create(String issueKey, JiraComment comment) {
			return withRetry(() -> delegate.create(issueKey, comment));
		}

		@Override
		public JiraIssueResponse update(String issueKey, String commentId, JiraComment comment) {
			return withRetry(() -> delegate.update(issueKey, commentId, comment));
		}

		@Override
		public List<JiraSimpleObject> getPriorities() {
			return withRetry(delegate::getPriorities);
		}

		@Override
		public List<JiraSimpleObject> getIssueTypes() {
			return withRetry(delegate::getIssueTypes);
		}

		@Override
		public List<JiraSimpleObject> getStatues() {
			return withRetry(delegate::getStatues);
		}

		@Override
		public JiraIssueLinkTypes getIssueLinkTypes() {
			return withRetry(delegate::getIssueLinkTypes);
		}

		@Override
		public List<JiraUser> findUser(String email) {
			return withRetry(() -> delegate.findUser(email));
		}

		@Override
		public JiraIssueLink getIssueLink(Long id) {
			return withRetry(() -> delegate.getIssueLink(id));
		}

		@Override
		public void upsertIssueLink(JiraIssueLink link) {
			withRetry(() -> delegate.upsertIssueLink(link));
		}

		@Override
		public void deleteComment(String issueKey, String commentId) {
			withRetry(() -> delegate.deleteComment(issueKey, commentId));
		}

		@Override
		public void deleteIssueLink(String linkId) {
			withRetry(() -> delegate.deleteIssueLink(linkId));
		}

		@Override
		public JiraIssues find(String query, String nextPageToken, int maxResults, List<String> fields) {
			return withRetry(() -> delegate.find(query, nextPageToken, maxResults, fields));
		}

		@Override
		public JiraIssues find(String query, int startAt, int maxResults) {
			return withRetry(() -> delegate.find(query, startAt, maxResults));
		}

		@Override
		public void transition(String issueKey, JiraTransition transition) {
			withRetry(() -> delegate.transition(issueKey, transition));
		}

		@Override
		public JiraTransitions availableTransitions(String issueKey) {
			return withRetry(() -> delegate.availableTransitions(issueKey));
		}

		@Override
		public void archive(String issueKey) {
			withRetry(() -> delegate.archive(issueKey));
		}

		@Override
		public JiraVersion version(Long id) {
			return withRetry(() -> delegate.version(id));
		}

		@Override
		public List<JiraVersion> versions(String projectKey) {
			return withRetry(() -> delegate.versions(projectKey));
		}

		@Override
		public JiraVersion create(JiraVersion version) {
			return withRetry(() -> delegate.create(version));
		}

		@Override
		public JiraVersion update(String id, JiraVersion version) {
			return withRetry(() -> delegate.update(id, version));
		}

		@Override
		public void assign(String id, JiraUser assignee) {
			withRetry(() -> delegate.assign(id, assignee));
		}

		@Override
		public JiraProject project(String projectId) {
			return withRetry(() -> delegate.project(projectId));
		}

		private static final int RETRIES = 5;
		private static final Duration WAIT_BETWEEN_RETRIES = Duration.of(2, ChronoUnit.SECONDS);

		private void withRetry(Runnable runnable) {
			withRetry(() -> {
				runnable.run();
				return null;
			});
		}

		private <T> T withRetry(Supplier<T> supplier) {
			return withRetry(supplier, RETRIES);
		}

		private <T> T withRetry(Supplier<T> supplier, int retries) {
			RuntimeException e = null;
			for (int i = 0; i < retries; i++) {
				try {
					return supplier.get();
				} catch (RuntimeException exception) {
					if (!shouldRetryOnException(exception)) {
						throw exception;
					}
					e = exception;
				}
				try {
					Thread.sleep(WAIT_BETWEEN_RETRIES);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				}
			}
			throw e;
		}

		private boolean shouldRetryOnException(Throwable throwable) {
			if (throwable instanceof JiraRestException exception) {
				if (exception.statusCode() == RestResponse.StatusCode.UNAUTHORIZED
						|| exception.statusCode() == RestResponse.StatusCode.FORBIDDEN) {
					Log.warnf(exception,
							"Will make an attempt to retry the REST API call because of the authentication problem. Response headers %s",
							exception.headers());
					return true;
				}
				if (exception.statusCode() == RestResponse.StatusCode.NOT_FOUND) {
					// not found is fine :)
					Log.warn("Will make no retry attempt of a REST API call for a NOT_FOUND response.");
					return false;
				}
				if (Response.Status.Family.SERVER_ERROR
						.equals(Response.Status.Family.familyOf(exception.statusCode()))) {
					Log.warnf(exception,
							"Will make an attempt to retry the REST API call because of the internal server problem. Response headers %s",
							exception.headers());
					return true;
				}
				if (Response.Status.Family.CLIENT_ERROR.equals(Response.Status.Family.familyOf(exception.statusCode()))
						&& exception.getMessage().contains("\"assignee\"")) {
					// we probably were trying to assign to an inactive or incorrectly configured
					// user and the request failed,
					// no point in retrying that ...
					return false;
				}
				if (Response.Status.TOO_MANY_REQUESTS.getStatusCode() == exception.statusCode()) {
					// we probably were trying to assign to an inactive or incorrectly configured
					// user and the request failed,
					// no point in retrying that ...
					return false;
				}
			}
			return false;
		}
	}

}
