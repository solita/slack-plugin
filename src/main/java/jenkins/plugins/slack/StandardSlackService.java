package jenkins.plugins.slack;

import com.google.common.annotations.VisibleForTesting;
import hudson.ProxyConfiguration;
import hudson.model.Run;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.plugins.slack.user.SlackUserIdResolver;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

public class StandardSlackService implements SlackService {

    private static final Logger logger = Logger.getLogger(StandardSlackService.class.getName());
    private static final Pattern JENKINS_CI_HOOK_REGEX = Pattern.compile("https://(?<teamDomain>.*)\\.slack\\.com/services/hooks/jenkins-ci.*");

    private Run run;
    private String baseUrl;
    private String teamDomain;
    private boolean botUser;
    private List<String> roomIds;
    private boolean replyBroadcast;
    private boolean sendAsText;
    private String iconEmoji;
    private String username;
    private String responseString;
    private String populatedToken;
    private boolean notifyCommitters;
    private SlackUserIdResolver userIdResolver;


    /**
     * @deprecated use {@link #StandardSlackService(String, String, boolean, String, boolean, String)} instead}
     */
    @Deprecated
    public StandardSlackService(String baseUrl, String teamDomain, String authTokenCredentialId, boolean botUser, String roomId) {
        this(baseUrl, teamDomain, null, authTokenCredentialId, botUser, roomId, false);
    }

    /**
     * @deprecated use {@link #StandardSlackService(String, String, boolean, String, boolean, String)} instead}
     */
    @Deprecated
    public StandardSlackService(String baseUrl, String teamDomain, String token, String authTokenCredentialId, boolean botUser, String roomId) {
        this(baseUrl, teamDomain, token, authTokenCredentialId, botUser, roomId, false);
    }

    /**
     * @deprecated use {@link #StandardSlackService(String, String, boolean, String, boolean, String)} instead}
     */
    @Deprecated
    public StandardSlackService(String baseUrl, String teamDomain, String token, String authTokenCredentialId, boolean botUser, String roomId, boolean replyBroadcast) {

        this(baseUrl, teamDomain, botUser, roomId, replyBroadcast, authTokenCredentialId);
        this.populatedToken = getTokenToUse(authTokenCredentialId, token);
        if (this.populatedToken == null) {
            throw new IllegalArgumentException("No slack token found, setup a secret text credential and configure it to be used");
        }
    }

    @Deprecated
    public StandardSlackService(String baseUrl, String teamDomain, boolean botUser, String roomId, boolean replyBroadcast, String populatedToken) {
        this(builder()
                .withBaseUrl(baseUrl)
                .withTeamDomain(teamDomain)
                .withBotUser(botUser)
                .withRoomId(roomId)
                .withReplyBroadcast(replyBroadcast)
                .withPopulatedToken(populatedToken)
        );
        if (populatedToken == null) {
            throw new IllegalArgumentException("No slack token found, setup a secret text credential and configure it to be used");
        }
        this.populatedToken = populatedToken;
    }

    public StandardSlackService(StandardSlackServiceBuilder standardSlackServiceBuilder) {
        this.run = standardSlackServiceBuilder.run;
        this.baseUrl = standardSlackServiceBuilder.baseUrl;
        if (this.baseUrl != null && !this.baseUrl.isEmpty() && !this.baseUrl.endsWith("/")) {
            this.baseUrl += "/";
        }
        this.teamDomain = standardSlackServiceBuilder.teamDomain;
        this.botUser = standardSlackServiceBuilder.botUser;
        if (standardSlackServiceBuilder.roomId == null) {
            throw new IllegalArgumentException("Project Channel or Slack User ID must be specified.");
        }
        this.roomIds = new ArrayList<>(Arrays.asList(standardSlackServiceBuilder.roomId.split("[,; ]+")));
        this.replyBroadcast = standardSlackServiceBuilder.replyBroadcast;
        this.iconEmoji = correctEmojiFormat(standardSlackServiceBuilder.iconEmoji);
        this.username = standardSlackServiceBuilder.username;
        this.populatedToken = standardSlackServiceBuilder.populatedToken;
        this.notifyCommitters = standardSlackServiceBuilder.notifyCommitters;
        this.userIdResolver = standardSlackServiceBuilder.userIdResolver;
    }

    public static StandardSlackServiceBuilder builder() {
        return new StandardSlackServiceBuilder();
    }

    public String getResponseString() {
        return responseString;
    }

    public boolean publish(String message) {
        return publish(message, "warning");
    }

    /**
     * The slack jenkins CI app documentation is incorrect, but they haven't updated it after asking
     * This confused users and causes them to mis-configure the application
     * We correct it to reduce the amount of support needed
     */
    void correctMisconfigurationOfBaseUrl() {
        Matcher matcher = JENKINS_CI_HOOK_REGEX.matcher(baseUrl);
        if (StringUtils.isNotEmpty(baseUrl) && matcher.matches()) {
            teamDomain = matcher.group("teamDomain");
            logger.warning("Overriding base url to team domain '" + teamDomain + "' this is due to " +
                    "mis-configuration, you don't need to set base url unless you're using a slack compatible app like mattermost");
            botUser = false;
        }
    }

    @Override
    public boolean publish(SlackRequest slackRequest) {
        boolean result = true;

        String message = slackRequest.getMessage();
        JSONArray attachments = slackRequest.getAttachments();
        JSONArray blocks = slackRequest.getBlocks();
        String color = slackRequest.getColor();

        try (CloseableHttpClient client = getHttpClient()) {
            // include committer userIds in roomIds
            if (botUser && notifyCommitters && userIdResolver != null && run != null) {
                userIdResolver.setAuthToken(populatedToken);
                userIdResolver.setHttpClient(client);
                List<String> userIds = userIdResolver.resolveUserIdsForRun(run);
                roomIds.addAll(userIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .map(userId -> "@" + userId)
                        .collect(Collectors.toList())
                );
            }

            for (String roomId : roomIds) {
                HttpPost post;
                String url;
                String threadTs = "";

                //thread_ts is passed once with roomId: Ex: roomId:threadTs
                String[] splitThread = roomId.split("[:]+");
                if (splitThread.length > 1) {
                    roomId = splitThread[0];
                    threadTs = splitThread[1];
                }
                JSONObject json = new JSONObject();
                json.put("channel", roomId);
                if (StringUtils.isNotEmpty(message)) {
                    json.put("text", message);
                }
                if (attachments != null && !attachments.isEmpty()) {
                    json.put("attachments", attachments);
                }

                if (blocks != null && !blocks.isEmpty()) {
                    json.put("blocks", blocks);
                }
                json.put("link_names", "1");
                json.put("unfurl_links", "true");
                json.put("unfurl_media", "true");

                if (baseUrl != null) {
                    correctMisconfigurationOfBaseUrl();
                }

                //prepare post methods for both requests types
                if (!botUser || StringUtils.isNotEmpty(baseUrl)) {
                    url = "https://" + teamDomain + "." + "slack.com" + "/services/hooks/jenkins-ci?token=" + populatedToken;
                    if (!StringUtils.isEmpty(baseUrl)) {
                        url = baseUrl + populatedToken;
                    }
                    post = new HttpPost(url);

                } else {
                    url = "https://slack.com/api/chat.postMessage";

                    post = new HttpPost(url);
                    post.setHeader("Authorization", "Bearer " + populatedToken);
                    if (threadTs.length() > 1) {
                        json.put("thread_ts", threadTs);
                    }
                    if (replyBroadcast) {
                        json.put("reply_broadcast", "true");
                    }
                    if (StringUtils.isEmpty(iconEmoji) && StringUtils.isEmpty(username)) {
                        json.put("as_user", "true");
                    } else {
                        if (StringUtils.isNotEmpty(iconEmoji)) {
                            json.put("icon_emoji", iconEmoji);
                        }
                        if (StringUtils.isNotEmpty(username)) {
                            json.put("username", username);
                        }
                    }
                }

                logger.fine("Posting: to " + roomId + " on " + teamDomain + " using " + url + ":  " + color);

                post.setHeader("Content-Type", "application/json; charset=utf-8");
                post.setEntity(new StringEntity(json.toString(), StandardCharsets.UTF_8));

                try (CloseableHttpResponse response = client.execute(post)) {
                    int responseCode = response.getStatusLine().getStatusCode();
                    HttpEntity entity = response.getEntity();
                    if (botUser && entity != null) {
                        responseString = EntityUtils.toString(entity);
                        try {

                            org.json.JSONObject slackResponse = new org.json.JSONObject(responseString);
                            result = slackResponse.getBoolean("ok");
                        } catch (org.json.JSONException ex) {
                            logger.log(Level.WARNING, "Slack post may have failed.  Invalid JSON response: " + responseString);
                            result = false;
                        }
                    }
                    if (responseCode != HttpStatus.SC_OK || !result) {
                        logger.log(Level.WARNING, "Slack post may have failed. Response: " + responseString);
                        logger.log(Level.WARNING, "Response Code: " + responseCode);
                        result = false;
                    } else {
                        logger.fine("Posting succeeded");
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error posting to Slack", e);
                    result = false;
                } finally {
                    post.releaseConnection();
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Error closing HttpClient", e);
        }
        return result;
    }

    @Override
    public boolean publish(String message, String color) {
        //prepare attachments first
        JSONObject field = new JSONObject();
        field.put("short", false);
        field.put("value", message);

        JSONArray fields = new JSONArray();
        fields.add(field);

        JSONObject attachment = new JSONObject();
        attachment.put("fallback", message);
        attachment.put("color", color);
        attachment.put("fields", fields);
        JSONArray mrkdwn = new JSONArray();
        mrkdwn.add("pretext");
        mrkdwn.add("text");
        mrkdwn.add("fields");
        attachment.put("mrkdwn_in", mrkdwn);
        JSONArray attachments = new JSONArray();
        attachments.add(attachment);

        return publish(null, attachments, color);
    }

    @Override
    public boolean publish(String message, JSONArray attachments, String color) {
        return publish(
                SlackRequest.builder()
                        .withMessage(message)
                        .withAttachments(attachments)
                        .withColor(color)
                        .build()
        );
    }


    private String getTokenToUse(String authTokenCredentialId, String token) {
        if (!StringUtils.isEmpty(authTokenCredentialId)) {
            StringCredentials credentials = CredentialsObtainer.lookupCredentials(authTokenCredentialId);
            if (credentials != null) {
                logger.fine("Using Integration Token Credential ID.");
                return credentials.getSecret().getPlainText();
            }
        }

        logger.fine("Using Integration Token.");
        return token;
    }

    private String correctEmojiFormat(String iconEmoji) {
        if (StringUtils.isEmpty(iconEmoji)) {
            return iconEmoji;
        }
        iconEmoji = StringUtils.appendIfMissing(iconEmoji, ":");
        iconEmoji = StringUtils.prependIfMissing(iconEmoji, ":");

        return iconEmoji;
    }

    protected CloseableHttpClient getHttpClient() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        ProxyConfiguration proxy = jenkins != null ? jenkins.proxy : null;
        return HttpClient.getCloseableHttpClient(proxy);
    }

    @VisibleForTesting
    String getTeamDomain() {
        return teamDomain;
    }
}
