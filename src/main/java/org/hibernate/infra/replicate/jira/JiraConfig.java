package org.hibernate.infra.replicate.jira;

import java.net.URI;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "jira")
public interface JiraConfig {

	/**
	 * Describes the sync configuration of a project group. A number of projects
	 * that live in one Jira instance and is synced to another (single) Jira
	 * instance is considered a project group.
	 * <p>
	 * It's where all the mapping and connection details can be defined/customized.
	 */
	Map<String, JiraProjectGroup> projectGroup();

	interface JiraProjectGroup {
		/**
		 * Configuration of the source (upstream) Jira instance.
		 */
		Instance source();

		/**
		 * Configuration of the destination (downstream) Jira instance.
		 */
		Instance destination();

		/**
		 * Mapping of upstream users to downstream users. If a "not mapped user" is
		 * encountered while syncing the issue/comment then the Service Account will be
		 * set as the reporter, or "unassigned" for the assignee.
		 */
		UserValueMapping users();

		/**
		 * Mapping of upstream priorities to downstream ones. Please make sure to review
		 * your project scheme to see which priorities are available.
		 */
		ValueMapping priorities();

		/**
		 * Mapping of upstream issue link types to downstream ones. Please make sure to
		 * review your project scheme to see which issue link types are available.
		 */
		IssueLinkTypeValueMapping issueLinkTypes();

		/**
		 * Mapping of upstream statuses to downstream transitions. Please make sure to
		 * review your project scheme to see which transitions can be used to set the
		 * desired status.
		 */
		StatusesValueMapping statuses();

		/**
		 * Same as {@link #statuses()}, but to map downstream statuses to upstream one
		 * for the backwards sync.
		 */
		Optional<StatusesValueMapping> downstreamStatuses();

		/**
		 * Mapping of upstream issue types to downstream ones. Please make sure to
		 * review your project scheme to see which issue types are available.
		 */
		IssueTypeValueMapping issueTypes();

		/**
		 * Depending on your downstream JIRA permission schema configuration, Service
		 * Account may not have permissions to edit the reporter field. In that case
		 * keep the value as default {@code false}, which will result in reporter being
		 * set as the Service Account. Otherwise, if {@code true} is set, the tool will
		 * attempt to set the mapped user as a reporter in the update request.
		 */
		@WithDefault("false")
		boolean canSetReporter();

		Map<String, JiraProject> projects();

		/**
		 * If enabled, will run a cron job that takes latest updated tickets in the
		 * project group and "re-sync" them.
		 */
		Scheduled scheduled();

		/**
		 * Usually the Jira instance would have some request limits, look for
		 * {@code x-ratelimit-*} headers in the responses from the REST API.
		 * <p>
		 * To not overwhelm the Jira instance with requests, the number of events
		 * processed through a period of time can be limited. During each specified
		 * timeframe a certain number of events can be processed. If the number of
		 * events to process exceed the limit, the next event will get blocked waiting
		 * for the timeframe to end and will get process
		 */
		EventProcessing processing();

		/**
		 * Allows customizing formatting options.
		 */
		Formatting formatting();

		/**
		 * Allows enabling signature verification.
		 */
		WebHookSecurity security();
	}

	interface Formatting {

		/**
		 * Specify how the label is formatted for a downstream issue.
		 * <p>
		 * Template receives a single String token that is an upstream label. {@code %s}
		 * <b>must</b> be used in the template as it will be replaced by a {@code .+}
		 * regex to find matches in the already synced labels.
		 */
		@WithDefault("upstream-%s")
		String labelTemplate();

		/**
		 * Specify how {@link java.time.ZonedDateTime} is formatted to string.
		 */
		@WithDefault("EEEE, MMMM dd, yyyy 'at' HH:mm:ss z(Z)")
		String timestampFormat();
	}

	interface EventProcessing {

		/**
		 * Define how many events can be acknowledged and put on the pending queue
		 * before acknowledging an event results in blocking the response and waiting
		 * for the queue to free some space.
		 */
		@WithDefault("10000")
		int queueSize();

		/**
		 * Define the number of threads to use when processing queued events.
		 * <p>
		 * Note, having a lot of processing threads might not bring much benefit as
		 * processing may also be limited by {@link JiraConfig.EventProcessing}
		 */
		@WithDefault("2")
		int threads();

		/**
		 * Defines how many events can be processed within the
		 * {@link #timeframeInSeconds() timeframe}
		 */
		@WithDefault("5")
		int eventsPerTimeframe();

		/**
		 * Define the duration of the timeframe. Each timeframe will allow to precess
		 * the defined number of events and blocking any others to wait for the next
		 * timeframe.
		 */
		@WithDefault("2")
		int timeframeInSeconds();
	}

	interface Scheduled {
		/**
		 * Specify the cron string to define when the "re-sync" should be performed.
		 */
		String cron();

		/**
		 * Specify for how many tickets to look for. By default ({@code -1d} the tool
		 * will take the tickets updated during the last 24 hours. See JQL to learn more
		 * on possible query values for this filer.
		 */
		@WithDefault("-1d")
		String timeFilter();
	}

	interface JiraProject {
		/**
		 * Downstream project id (not a project key!). Use
		 * {@code rest/api/2/project/YOUR-PROJECT-ID} to get the info.
		 */
		String projectId();

		/**
		 * Downstream project key. Use {@code rest/api/2/project/YOUR-PROJECT-ID} to get
		 * the info.
		 */
		String projectKey();

		/**
		 * Upstream project key. Use {@code rest/api/2/project/YOUR-PROJECT-ID} to get
		 * the info.
		 */
		String originalProjectKey();

		/**
		 * Allows enabling signature verification of downstream events.
		 */
		WebHookSecurity downstreamSecurity();
	}

	interface WebHookSecurity {
		/**
		 * Whether to enable signature verification.
		 * <p>
		 * Jira web hooks can send a {@code x-hub-signature} header with a signature of
		 * a request body. This signature can be then verified using the secret used to
		 * configure the web hook.
		 */
		@WithDefault("false")
		boolean enabled();

		@WithDefault("SIGNATURE")
		Type type();

		/**
		 * Verification secret, e.g. the secret used to sing the web hook request body.
		 * Can also be just some token that we will compare. Depends on the security
		 * type.
		 */
		@WithDefault("not-a-secret")
		String secret();

		enum Type {
			SIGNATURE, TOKEN
		}
	}

	interface Instance {
		@WithDefault("BASIC")
		LoginKind loginKind();

		JiraUser apiUser();

		URI apiUri();

		/**
		 * Whether to log request/response bodies of a Jira REST API calls.
		 */
		@WithDefault("false")
		boolean logRequests();
	}

	interface JiraUser {
		/**
		 * Service account email/username to use for basic authentication.
		 */
		String email();

		/**
		 * A personal authentication token for the service account.
		 */
		String token();
	}

	interface ValueMapping {
		/**
		 * @return default value for the downstream JIRA.
		 */
		String defaultValue();

		Map<String, String> mapping();

	}

	interface IssueLinkTypeValueMapping extends ValueMapping {
		/**
		 * @return the value of the issue link type id to use for linking a "sub-task"
		 *         ticket to a "parent" NOTE: Since changing issue to a subtask doesn't
		 *         work through the REST API we are creating a regular task instead and
		 *         adding an extra link for it.
		 */
		String parentLinkType();

		/**
		 * @return the name to be set as a part of the remote link request in the
		 *         `application` object i.e. the name of "remote jira" as configured on
		 *         your server.
		 */
		Optional<String> applicationNameForRemoteLinkType();

		/**
		 * @return the appId to be used to create a globalId for a remote link, e.g.:
		 *         {@code "globalId": "appId=5e7d6222-8225-3bcd-be58-5fe3980b0fae&issueId=65806"}
		 */
		Optional<String> applicationIdForRemoteLinkType();

		/**
		 * @return the appId to be used to create a globalId for a remote link, e.g.:
		 *         Jira Cloud has the format:
		 *         {@code "globalId": "system=http://www.mycompany.com/support&id=1"}
		 */
		Optional<String> systemForRemoteLinkType();
	}

	interface IssueTypeValueMapping extends ValueMapping {
		/**
		 * @return the name of the custom field that represents the epic link field.
		 *         Apparently there is no clean way to attach issues to an epic, and a
		 *         possible workaround is to use the custom filed (that differs from
		 *         instance to instance) that represents the Epic link key.
		 *         <p>
		 *         A possible alternative could've been <a href=
		 *         "https://developer.atlassian.com/server/jira/platform/rest/v10000/api-group-epic/#api-agile-1-0-epic-epicidorkey-issue-post">Move
		 *         issues to a specific epic</a> but it might not be available on all
		 *         instance types.
		 *         <p>
		 *         If value is not provided in the configuration, then tickets will not
		 *         be linked to epics during the sync.
		 */
		Optional<String> epicLinkKeyCustomFieldName();

		/**
		 * @return The name of a custom field that represents the "epic name" i.e.
		 *         epic-short-label in the upstream (source) Jira instance.
		 */
		Optional<String> epicLinkSourceLabelCustomFieldName();

		/**
		 * @return The name of a custom field that represents the "epic name" i.e.
		 *         epic-short-label in the downstream (destination) Jira instance.
		 */
		Optional<String> epicLinkDestinationLabelCustomFieldName();
	}

	interface StatusesValueMapping extends ValueMapping {
		/**
		 * @return The name of the resolution to apply to the "Close" transition when
		 *         closing the issue deleted upstream before archiving it.
		 */
		Optional<String> deletedResolution();

		/**
		 * @return The status name to transition the issue to when handling an issue
		 *         deleted/moved upstream before archiving it.
		 */
		Optional<String> deletedStatus();

		/**
		 * @return A map where the {@code key} is the upstream status name, and the
		 *         corresponding {@code value} contains the names of the downstream
		 *         status names. If the downstream issue is currently in one of the
		 *         statuses present in the value set then the transition for status
		 *         ({@code key}) should not be applied.
		 *         <p>
		 *         This allows both skipping unnecessary transitions
		 *         {@code "status a" -> "status a" } as well as to keep the downstream
		 *         issue in the status modified downstream if it is within the "group"
		 *         of statuses "mapped" to the upstream one. This can be useful when
		 *         e.g. downstream workflow has both {@code New} and {@code Ready}
		 *         statuses, and users move new issues to the {@code Ready} status
		 *         issues downstream, without an override from an upstream updates.
		 */
		Map<String, Set<String>> ignoreTransitionCondition();
	}

	interface UserValueMapping extends ValueMapping {
		/**
		 * @return the name of the property to apply the assignee value to. With Jira
		 *         Server the required property is {@code name}, while for the Cloud an
		 *         {@code accountId} is expected.
		 */
		@WithDefault("accountId")
		String mappedPropertyName();

		/**
		 * By default, if the assignee of the upstream issue is not mapped, the project
		 * default will be used (i.e. assignee field will not be sent as part of the
		 * sync request). If the {@code notMappedAssignee} is provided, this value will
		 * be set as assignee for those issues where an upstream assignee is not mapped
		 * to a downstream one. This can be useful to show that the issue is assign to
		 * someone even though that particular user is not present in the downstream
		 * Jira.
		 */
		Optional<String> notMappedAssignee();

		/**
		 * Allows specifying an URL template that will be passed exactly 1 argument
		 * (mapped user value). E.g. a template can look like
		 * {@code https://my-jira-server/secure/ViewProfile.jspa?name={arg1}}, where
		 * {@code arg1} is the mapped user value from {@link #mapping()}. If not
		 * specified the link to the upstream profile will be created with a template of
		 * {@code https://my-upstream-jira-server/jira/people/{arg1}}, where
		 * {@code arg1} is the original user id i.e. the {@link #mapping() mapping key}.
		 * <p>
		 * Note, this profile URL only applies to the profiles that have a defined
		 * {@link #mapping() mapping}.
		 */
		Optional<String> profileUrl();

		/**
		 * It may be helpful in some cases to ignore webhook events triggered by some
		 * users. E.g. the sync user that applies updates upstream can be listed here to
		 * prevent infinite update loop.
		 */
		@WithDefault("not-a-user")
		Set<String> ignoredUpstreamUsers();

		@WithDefault("not-a-user")
		Set<String> ignoredDownstreamUsers();
	}

	/**
	 * Some JIRA instances may allow PAT logins (personal authentication tokens)
	 * while others: basic authentication with a username password/token
	 */
	enum LoginKind {
		/**
		 * Basic authentication with a username password/token. A string
		 * {@code username:password} is {@code base64}-encoded and passed in the auth
		 * header.
		 */
		BASIC {
			@Override
			public Map<String, String> headers(String username, String token) {
				return Map.of("Authorization", "Basic %s".formatted(
						Base64.getEncoder().encodeToString(("%s:%s".formatted(username, token)).getBytes())));
			}
		},
		/**
		 * A PAT login, where a JIRA generated token is simply passed as is in the
		 * header without any additional encoding, usernames or anything else. See also
		 * <a href=
		 * "https://confluence.atlassian.com/enterprise/using-personal-access-tokens-1026032365.html">personal
		 * access tokens</a>
		 */
		BEARER_TOKEN {
			@Override
			public Map<String, String> headers(String username, String token) {
				return Map.of("Authorization", "Bearer %s".formatted(token));
			}
		};

		public abstract Map<String, String> headers(String username, String token);
	}
}
