/**
 * Copyright © 2016-2024 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.notification;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.SettableFuture;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.data.Offset;
import org.java_websocket.client.WebSocketClient;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import org.thingsboard.rule.engine.api.NotificationCenter;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.NotificationRequestId;
import org.thingsboard.server.common.data.id.NotificationRuleId;
import org.thingsboard.server.common.data.id.NotificationTargetId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.notification.Notification;
import org.thingsboard.server.common.data.notification.NotificationDeliveryMethod;
import org.thingsboard.server.common.data.notification.NotificationRequest;
import org.thingsboard.server.common.data.notification.NotificationRequestConfig;
import org.thingsboard.server.common.data.notification.NotificationRequestInfo;
import org.thingsboard.server.common.data.notification.NotificationRequestPreview;
import org.thingsboard.server.common.data.notification.NotificationRequestStats;
import org.thingsboard.server.common.data.notification.NotificationRequestStatus;
import org.thingsboard.server.common.data.notification.NotificationType;
import org.thingsboard.server.common.data.notification.info.EntityActionNotificationInfo;
import org.thingsboard.server.common.data.notification.settings.NotificationSettings;
import org.thingsboard.server.common.data.notification.settings.SlackNotificationDeliveryMethodConfig;
import org.thingsboard.server.common.data.notification.settings.UserNotificationSettings;
import org.thingsboard.server.common.data.notification.targets.MicrosoftTeamsNotificationTargetConfig;
import org.thingsboard.server.common.data.notification.targets.NotificationTarget;
import org.thingsboard.server.common.data.notification.targets.platform.CustomerUsersFilter;
import org.thingsboard.server.common.data.notification.targets.platform.PlatformUsersNotificationTargetConfig;
import org.thingsboard.server.common.data.notification.targets.platform.SystemAdministratorsFilter;
import org.thingsboard.server.common.data.notification.targets.platform.UserListFilter;
import org.thingsboard.server.common.data.notification.targets.slack.SlackConversation;
import org.thingsboard.server.common.data.notification.targets.slack.SlackConversationType;
import org.thingsboard.server.common.data.notification.targets.slack.SlackNotificationTargetConfig;
import org.thingsboard.server.common.data.notification.template.DeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.EmailDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.MicrosoftTeamsDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.MicrosoftTeamsDeliveryMethodNotificationTemplate.Button.LinkType;
import org.thingsboard.server.common.data.notification.template.NotificationTemplate;
import org.thingsboard.server.common.data.notification.template.NotificationTemplateConfig;
import org.thingsboard.server.common.data.notification.template.SlackDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.SmsDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.notification.template.WebDeliveryMethodNotificationTemplate;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.dao.notification.DefaultNotifications;
import org.thingsboard.server.dao.notification.NotificationDao;
import org.thingsboard.server.dao.service.DaoSqlTest;
import org.thingsboard.server.service.executors.DbCallbackExecutorService;
import org.thingsboard.server.service.notification.channels.MicrosoftTeamsNotificationChannel;
import org.thingsboard.server.service.ws.notification.cmd.UnreadNotificationsUpdate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.type;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@DaoSqlTest
@Slf4j
public class NotificationApiTest extends AbstractNotificationApiTest {

    @Autowired
    private NotificationCenter notificationCenter;
    @Autowired
    private NotificationDao notificationDao;
    @Autowired
    private DbCallbackExecutorService executor;
    @Autowired
    private MicrosoftTeamsNotificationChannel microsoftTeamsNotificationChannel;

    @Before
    public void beforeEach() throws Exception {
        loginCustomerUser();
        wsClient = getWsClient();
        loginTenantAdmin();
    }

    @Test
    public void testSubscribingToUnreadNotificationsCount() {
        wsClient.subscribeForUnreadNotificationsCount().waitForReply(true);
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        String notificationText1 = "Notification 1";
        submitNotificationRequest(notificationTarget.getId(), notificationText1);
        String notificationText2 = "Notification 2";
        submitNotificationRequest(notificationTarget.getId(), notificationText2);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> wsClient.getLastCountUpdate().getTotalUnreadCount() == 2);
    }

    @Test
    public void testReceivingCountUpdates_multipleSessions() throws Exception {
        connectOtherWsClient();
        wsClient.subscribeForUnreadNotificationsCount();
        otherWsClient.subscribeForUnreadNotificationsCount();
        wsClient.waitForReply(true);
        otherWsClient.waitForReply(true);
        assertThat(wsClient.getLastCountUpdate().getTotalUnreadCount()).isZero();

        wsClient.registerWaitForUpdate();
        otherWsClient.registerWaitForUpdate();
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        String notificationText = "Notification";
        submitNotificationRequest(notificationTarget.getId(), notificationText);
        wsClient.waitForUpdate(true);
        otherWsClient.waitForUpdate(true);

        assertThat(wsClient.getLastCountUpdate().getTotalUnreadCount()).isOne();
        assertThat(otherWsClient.getLastCountUpdate().getTotalUnreadCount()).isOne();
    }

    @Test
    public void testSubscribingToUnreadNotifications_multipleSessions() throws Exception {
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        String notificationText1 = "Notification 1";
        submitNotificationRequest(notificationTarget.getId(), notificationText1);
        String notificationText2 = "Notification 2";
        submitNotificationRequest(notificationTarget.getId(), notificationText2);

        connectOtherWsClient();
        wsClient.subscribeForUnreadNotifications(10);
        otherWsClient.subscribeForUnreadNotifications(10);
        wsClient.waitForReply(true);
        otherWsClient.waitForReply(true);

        checkFullNotificationsUpdate(wsClient.getLastDataUpdate(), notificationText1, notificationText2);
        checkFullNotificationsUpdate(otherWsClient.getLastDataUpdate(), notificationText1, notificationText2);
    }

    @Test
    public void testReceivingNotificationUpdates_multipleSessions() throws Exception {
        connectOtherWsClient();
        wsClient.subscribeForUnreadNotifications(10).waitForReply(true);
        otherWsClient.subscribeForUnreadNotifications(10).waitForReply(true);
        UnreadNotificationsUpdate notificationsUpdate = wsClient.getLastDataUpdate();
        assertThat(notificationsUpdate.getTotalUnreadCount()).isZero();

        wsClient.registerWaitForUpdate();
        otherWsClient.registerWaitForUpdate();
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        String notificationText = "Notification 1";
        submitNotificationRequest(notificationTarget.getId(), notificationText);
        wsClient.waitForUpdate(true);
        otherWsClient.waitForUpdate(true);

        checkPartialNotificationsUpdate(wsClient.getLastDataUpdate(), notificationText, 1);
        checkPartialNotificationsUpdate(otherWsClient.getLastDataUpdate(), notificationText, 1);
    }

    @Test
    public void testMarkingAsRead_multipleSessions() throws Exception {
        connectOtherWsClient();
        wsClient.subscribeForUnreadNotifications(10).waitForReply(true);
        otherWsClient.subscribeForUnreadNotifications(10).waitForReply(true);

        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        wsClient.registerWaitForUpdate();
        otherWsClient.registerWaitForUpdate();
        String notificationText1 = "Notification 1";
        submitNotificationRequest(notificationTarget.getId(), notificationText1);
        wsClient.waitForUpdate(true);
        otherWsClient.waitForUpdate(true);
        Notification notification1 = wsClient.getLastDataUpdate().getUpdate();

        wsClient.registerWaitForUpdate();
        otherWsClient.registerWaitForUpdate();
        String notificationText2 = "Notification 2";
        submitNotificationRequest(notificationTarget.getId(), notificationText2);
        wsClient.waitForUpdate(true);
        otherWsClient.waitForUpdate(true);
        assertThat(wsClient.getLastDataUpdate().getTotalUnreadCount()).isEqualTo(2);
        assertThat(otherWsClient.getLastDataUpdate().getTotalUnreadCount()).isEqualTo(2);

        wsClient.registerWaitForUpdate();
        otherWsClient.registerWaitForUpdate();
        wsClient.markNotificationAsRead(notification1.getUuidId());
        wsClient.waitForUpdate(true);
        otherWsClient.waitForUpdate(true);

        checkFullNotificationsUpdate(wsClient.getLastDataUpdate(), notificationText2);
        checkFullNotificationsUpdate(otherWsClient.getLastDataUpdate(), notificationText2);
    }

    @Test
    public void testMarkingAllAsRead() {
        wsClient.subscribeForUnreadNotifications(10).waitForReply(true);
        NotificationTarget target = createNotificationTarget(customerUserId);
        int notificationsCount = 20;
        wsClient.registerWaitForUpdate(notificationsCount);
        for (int i = 1; i <= notificationsCount; i++) {
            submitNotificationRequest(target.getId(), "Test " + i, NotificationDeliveryMethod.WEB);
        }
        wsClient.waitForUpdate(true);
        assertThat(wsClient.getLastDataUpdate().getTotalUnreadCount()).isEqualTo(notificationsCount);

        wsClient.registerWaitForUpdate(1);
        wsClient.markAllNotificationsAsRead();
        wsClient.waitForUpdate(true);

        assertThat(wsClient.getLastDataUpdate().getNotifications()).isEmpty();
        assertThat(wsClient.getLastDataUpdate().getTotalUnreadCount()).isZero();
    }

    @Test
    public void testDelayedNotificationRequest() throws Exception {
        wsClient.subscribeForUnreadNotifications(5);
        wsClient.waitForReply(true);

        wsClient.registerWaitForUpdate();
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        String notificationText = "Was scheduled for 5 sec";
        NotificationRequest notificationRequest = submitNotificationRequest(notificationTarget.getId(), notificationText, 5);
        assertThat(notificationRequest.getStatus()).isEqualTo(NotificationRequestStatus.SCHEDULED);
        await().atLeast(4, TimeUnit.SECONDS)
                .atMost(15, TimeUnit.SECONDS)
                .until(() -> wsClient.getLastMsg() != null);

        Notification delayedNotification = wsClient.getLastDataUpdate().getUpdate();
        assertThat(delayedNotification).extracting(Notification::getText).isEqualTo(notificationText);
        assertThat(delayedNotification.getCreatedTime() - notificationRequest.getCreatedTime())
                .isCloseTo(TimeUnit.SECONDS.toMillis(5), Offset.offset(10000L));
        assertThat(findNotificationRequest(notificationRequest.getId()).getStatus()).isEqualTo(NotificationRequestStatus.SENT);
    }

    @Test
    public void whenNotificationRequestIsDeleted_thenDeleteNotifications() throws Exception {
        wsClient.subscribeForUnreadNotifications(10);
        wsClient.waitForReply(true);

        wsClient.registerWaitForUpdate();
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        NotificationRequest notificationRequest = submitNotificationRequest(notificationTarget.getId(), "Test");
        wsClient.waitForUpdate(true);
        assertThat(wsClient.getNotifications()).singleElement().extracting(Notification::getRequestId)
                .isEqualTo(notificationRequest.getId());
        assertThat(wsClient.getUnreadCount()).isOne();

        wsClient.registerWaitForUpdate();
        deleteNotificationRequest(notificationRequest.getId());
        wsClient.waitForUpdate(true);

        assertThat(wsClient.getNotifications()).isEmpty();
        assertThat(wsClient.getUnreadCount()).isZero();
        loginCustomerUser();
        assertThat(getMyNotifications(false, 10)).size().isZero();
    }

    @Test
    public void whenTenantIsDeleted_thenDeleteNotificationRequests() throws Exception {
        createDifferentTenant();
        TenantId tenantId = differentTenantId;
        NotificationTarget target = createNotificationTarget(savedDifferentTenantUser.getId());
        int notificationsCount = 20;
        for (int i = 0; i < notificationsCount; i++) {
            NotificationRequest request = submitNotificationRequest(target.getId(), "Test " + i, NotificationDeliveryMethod.WEB);
            awaitNotificationRequest(request.getId());
        }
        List<NotificationRequest> requests = notificationRequestService.findNotificationRequestsByTenantIdAndOriginatorType(tenantId, EntityType.USER, new PageLink(100)).getData();
        assertThat(requests).size().isEqualTo(notificationsCount);

        deleteDifferentTenant();

        assertThat(notificationRequestService.findNotificationRequestsByTenantIdAndOriginatorType(tenantId, EntityType.USER, new PageLink(1)).getTotalElements())
                .isZero();
    }

    @Test
    public void testNotificationUpdatesForSeveralUsers() throws Exception {
        int usersCount = 150;
        Map<User, NotificationApiWsClient> sessions = new HashMap<>();
        List<NotificationTargetId> targets = new ArrayList<>();

        for (int i = 1; i <= usersCount; i++) {
            User user = new User();
            user.setTenantId(tenantId);
            user.setAuthority(Authority.TENANT_ADMIN);
            user.setEmail("test-user-" + i + "@thingsboard.org");
            user = createUserAndLogin(user, "12345678");
            NotificationApiWsClient wsClient = buildAndConnectWebSocketClient();
            sessions.put(user, wsClient);

            NotificationTarget notificationTarget = createNotificationTarget(user.getId());
            targets.add(notificationTarget.getId());

            wsClient.registerWaitForUpdate();
            wsClient.subscribeForUnreadNotifications(10);
        }
        sessions.values().forEach(wsClient -> wsClient.waitForUpdate(true));

        loginTenantAdmin();

        sessions.forEach((user, wsClient) -> wsClient.registerWaitForUpdate());
        NotificationRequest notificationRequest = submitNotificationRequest(targets, "Hello, ${recipientEmail}", 0,
                NotificationDeliveryMethod.WEB);
        await().atMost(10, TimeUnit.SECONDS)
                .pollDelay(1, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS)
                .until(() -> {
                    long receivedUpdate = sessions.values().stream()
                            .filter(wsClient -> wsClient.getLastDataUpdate() != null)
                            .count();
                    System.err.println("WS sessions received update: " + receivedUpdate);
                    return receivedUpdate == sessions.size();
                });

        sessions.forEach((user, wsClient) -> {
            assertThat(wsClient.getLastDataUpdate().getTotalUnreadCount()).isOne();

            Notification notification = wsClient.getLastDataUpdate().getUpdate();
            assertThat(notification.getRecipientId()).isEqualTo(user.getId());
            assertThat(notification.getRequestId()).isEqualTo(notificationRequest.getId());
            assertThat(notification.getText()).isEqualTo("Hello, " + user.getEmail());
        });

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> findNotificationRequest(notificationRequest.getId()).isSent());
        NotificationRequestStats stats = getStats(notificationRequest.getId());
        assertThat(stats.getSent().get(NotificationDeliveryMethod.WEB)).hasValue(usersCount);

        sessions.values().forEach(wsClient -> wsClient.registerWaitForUpdate());
        deleteNotificationRequest(notificationRequest.getId());
        sessions.values().forEach(wsClient -> {
            wsClient.waitForUpdate(true);
            assertThat(wsClient.getLastDataUpdate().getNotifications()).isEmpty();
            assertThat(wsClient.getLastDataUpdate().getTotalUnreadCount()).isZero();
        });

        sessions.values().forEach(WebSocketClient::close);
    }

    @Test
    public void testNotificationRequestPreview() throws Exception {
        NotificationTarget tenantAdminTarget = new NotificationTarget();
        tenantAdminTarget.setName("Me");
        PlatformUsersNotificationTargetConfig tenantAdminTargetConfig = new PlatformUsersNotificationTargetConfig();
        UserListFilter userListFilter = new UserListFilter();
        userListFilter.setUsersIds(List.of(tenantAdminUserId.getId()));
        tenantAdminTargetConfig.setUsersFilter(userListFilter);
        tenantAdminTarget.setConfiguration(tenantAdminTargetConfig);
        tenantAdminTarget = saveNotificationTarget(tenantAdminTarget);
        List<String> recipients = new ArrayList<>();
        recipients.add(TENANT_ADMIN_EMAIL);
        String firstRecipientEmail = TENANT_ADMIN_EMAIL;

        createDifferentCustomer();
        loginTenantAdmin();
        int customerUsersCount = 10;
        for (int i = 0; i < customerUsersCount; i++) {
            User customerUser = new User();
            customerUser.setAuthority(Authority.CUSTOMER_USER);
            customerUser.setTenantId(tenantId);
            customerUser.setCustomerId(differentCustomerId);
            customerUser.setEmail("other-customer-" + i + "@thingsboard.org");
            customerUser = createUser(customerUser, "12345678");
            recipients.add(customerUser.getEmail());
        }
        NotificationTarget customerUsersTarget = new NotificationTarget();
        customerUsersTarget.setName("Other customer users");
        PlatformUsersNotificationTargetConfig customerUsersTargetConfig = new PlatformUsersNotificationTargetConfig();
        CustomerUsersFilter customerUsersFilter = new CustomerUsersFilter();
        customerUsersFilter.setCustomerId(differentCustomerId.getId());
        customerUsersTargetConfig.setUsersFilter(customerUsersFilter);
        customerUsersTarget.setConfiguration(customerUsersTargetConfig);
        customerUsersTarget = saveNotificationTarget(customerUsersTarget);

        NotificationTarget slackTarget = new NotificationTarget();
        slackTarget.setName("Slack user");
        SlackNotificationTargetConfig slackTargetConfig = new SlackNotificationTargetConfig();
        slackTargetConfig.setConversationType(SlackConversationType.DIRECT);
        SlackConversation slackConversation = new SlackConversation();
        slackConversation.setType(SlackConversationType.DIRECT);
        slackConversation.setId("U1234567");
        slackConversation.setName("jdoe");
        slackConversation.setWholeName("John Doe");
        slackTargetConfig.setConversation(slackConversation);
        slackTarget.setConfiguration(slackTargetConfig);
        slackTarget = saveNotificationTarget(slackTarget);
        recipients.add("@" + slackConversation.getWholeName());

        NotificationTemplate notificationTemplate = new NotificationTemplate();
        notificationTemplate.setNotificationType(NotificationType.GENERAL);
        notificationTemplate.setName("Test template");

        NotificationTemplateConfig templateConfig = new NotificationTemplateConfig();
        HashMap<NotificationDeliveryMethod, DeliveryMethodNotificationTemplate> templates = new HashMap<>();
        templateConfig.setDeliveryMethodsTemplates(templates);
        notificationTemplate.setConfiguration(templateConfig);

        WebDeliveryMethodNotificationTemplate webNotificationTemplate = new WebDeliveryMethodNotificationTemplate();
        webNotificationTemplate.setEnabled(true);
        webNotificationTemplate.setSubject("WEB SUBJECT: ${recipientEmail}");
        webNotificationTemplate.setBody("WEB: ${recipientEmail} ${unknownParam}");
        templates.put(NotificationDeliveryMethod.WEB, webNotificationTemplate);

        SmsDeliveryMethodNotificationTemplate smsNotificationTemplate = new SmsDeliveryMethodNotificationTemplate();
        smsNotificationTemplate.setEnabled(true);
        smsNotificationTemplate.setBody("SMS: ${recipientEmail}");
        templates.put(NotificationDeliveryMethod.SMS, smsNotificationTemplate);

        EmailDeliveryMethodNotificationTemplate emailNotificationTemplate = new EmailDeliveryMethodNotificationTemplate();
        emailNotificationTemplate.setEnabled(true);
        emailNotificationTemplate.setSubject("EMAIL SUBJECT: ${recipientEmail}");
        emailNotificationTemplate.setBody("EMAIL: ${recipientEmail}");
        templates.put(NotificationDeliveryMethod.EMAIL, emailNotificationTemplate);

        SlackDeliveryMethodNotificationTemplate slackNotificationTemplate = new SlackDeliveryMethodNotificationTemplate();
        slackNotificationTemplate.setEnabled(true);
        slackNotificationTemplate.setBody("SLACK: ${recipientFirstName} ${recipientLastName}");
        templates.put(NotificationDeliveryMethod.SLACK, slackNotificationTemplate);

        notificationTemplate = saveNotificationTemplate(notificationTemplate);

        NotificationRequest notificationRequest = new NotificationRequest();
        notificationRequest.setTargets(List.of(tenantAdminTarget.getUuidId(), customerUsersTarget.getUuidId(), slackTarget.getUuidId()));
        notificationRequest.setTemplateId(notificationTemplate.getId());
        notificationRequest.setAdditionalConfig(new NotificationRequestConfig());

        NotificationRequestPreview preview = doPost("/api/notification/request/preview", notificationRequest, NotificationRequestPreview.class);
        assertThat(preview.getRecipientsCountByTarget().get(tenantAdminTarget.getName())).isEqualTo(1);
        assertThat(preview.getRecipientsCountByTarget().get(customerUsersTarget.getName())).isEqualTo(customerUsersCount);
        assertThat(preview.getRecipientsCountByTarget().get(slackTarget.getName())).isEqualTo(1);

        assertThat(preview.getTotalRecipientsCount()).isEqualTo(2 + customerUsersCount);
        assertThat(preview.getRecipientsPreview()).containsAll(recipients);

        Map<NotificationDeliveryMethod, DeliveryMethodNotificationTemplate> processedTemplates = preview.getProcessedTemplates();
        assertThat(processedTemplates.get(NotificationDeliveryMethod.WEB)).asInstanceOf(type(WebDeliveryMethodNotificationTemplate.class))
                .satisfies(template -> {
                    assertThat(template.getSubject()).isEqualTo("WEB SUBJECT: " + firstRecipientEmail);
                    assertThat(template.getBody()).isEqualTo("WEB: " + firstRecipientEmail + " ${unknownParam}");
                });
        assertThat(processedTemplates.get(NotificationDeliveryMethod.SMS)).asInstanceOf(type(SmsDeliveryMethodNotificationTemplate.class))
                .satisfies(template -> {
                    assertThat(template.getBody()).isEqualTo("SMS: " + firstRecipientEmail);
                });
        assertThat(processedTemplates.get(NotificationDeliveryMethod.EMAIL)).asInstanceOf(type(EmailDeliveryMethodNotificationTemplate.class))
                .satisfies(template -> {
                    assertThat(template.getSubject()).isEqualTo("EMAIL SUBJECT: " + firstRecipientEmail);
                    assertThat(template.getBody()).isEqualTo("EMAIL: " + firstRecipientEmail);
                });
        assertThat(processedTemplates.get(NotificationDeliveryMethod.SLACK)).asInstanceOf(type(SlackDeliveryMethodNotificationTemplate.class))
                .satisfies(template -> {
                    assertThat(template.getBody()).isEqualTo("SLACK: John Doe");
                });
    }

    @Test
    public void testNotificationRequestInfo() throws Exception {
        NotificationDeliveryMethod[] deliveryMethods = new NotificationDeliveryMethod[]{
                NotificationDeliveryMethod.WEB
        };
        NotificationTemplate template = createNotificationTemplate(NotificationType.GENERAL, "Test subject", "Test text", deliveryMethods);
        NotificationTarget target = createNotificationTarget(tenantAdminUserId);
        NotificationRequest request = submitNotificationRequest(List.of(target.getId()), template.getId(), 0);

        NotificationRequestInfo requestInfo = findNotificationRequests().getData().get(0);
        assertThat(requestInfo.getId()).isEqualTo(request.getId());
        assertThat(requestInfo.getTemplateName()).isEqualTo(template.getName());
        assertThat(requestInfo.getDeliveryMethods()).containsOnly(deliveryMethods);
    }

    @Test
    public void testNotificationRequestStats() throws Exception {
        wsClient.subscribeForUnreadNotifications(10);
        wsClient.waitForReply(true);

        wsClient.registerWaitForUpdate();
        NotificationTarget notificationTarget = createNotificationTarget(customerUserId);
        NotificationRequest notificationRequest = submitNotificationRequest(notificationTarget.getId(), "Test :)", NotificationDeliveryMethod.WEB);
        wsClient.waitForUpdate();

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> findNotificationRequest(notificationRequest.getId()).isSent());
        NotificationRequestStats stats = getStats(notificationRequest.getId());

        assertThat(stats.getSent().get(NotificationDeliveryMethod.WEB)).hasValue(1);
    }

    @Test
    public void testUserNotificationSettings() throws Exception {
        var entityActionNotificationPref = new UserNotificationSettings.NotificationPref();
        entityActionNotificationPref.setEnabled(true);
        entityActionNotificationPref.setEnabledDeliveryMethods(Map.of(
                NotificationDeliveryMethod.WEB, true,
                NotificationDeliveryMethod.SMS, false,
                NotificationDeliveryMethod.EMAIL, false
        ));

        var entitiesLimitNotificationPref = new UserNotificationSettings.NotificationPref();
        entitiesLimitNotificationPref.setEnabled(true);
        entitiesLimitNotificationPref.setEnabledDeliveryMethods(Map.of(
                NotificationDeliveryMethod.SMS, true,
                NotificationDeliveryMethod.WEB, false,
                NotificationDeliveryMethod.EMAIL, false
        ));

        var apiUsageLimitNotificationPref = new UserNotificationSettings.NotificationPref();
        apiUsageLimitNotificationPref.setEnabled(false);
        apiUsageLimitNotificationPref.setEnabledDeliveryMethods(Map.of(
                NotificationDeliveryMethod.WEB, true,
                NotificationDeliveryMethod.SMS, false,
                NotificationDeliveryMethod.EMAIL, false
        ));

        UserNotificationSettings settings = new UserNotificationSettings(Map.of(
                NotificationType.ENTITY_ACTION, entityActionNotificationPref,
                NotificationType.ENTITIES_LIMIT, entitiesLimitNotificationPref,
                NotificationType.API_USAGE_LIMIT, apiUsageLimitNotificationPref
        ));
        doPost("/api/notification/settings/user", settings, UserNotificationSettings.class);

        var entityActionNotificationTemplate = createNotificationTemplate(NotificationType.ENTITY_ACTION, "Entity action", "Entity action", NotificationDeliveryMethod.WEB);
        var entitiesLimitNotificationTemplate = createNotificationTemplate(NotificationType.ENTITIES_LIMIT, "Entities limit", "Entities limit", NotificationDeliveryMethod.WEB);
        var apiUsageLimitNotificationTemplate = createNotificationTemplate(NotificationType.API_USAGE_LIMIT, "API usage limit", "API usage limit", NotificationDeliveryMethod.WEB);
        NotificationTarget target = createNotificationTarget(tenantAdminUserId);

        NotificationRequest notificationRequest = NotificationRequest.builder()
                .tenantId(tenantId)
                .templateId(entityActionNotificationTemplate.getId())
                .originatorEntityId(tenantAdminUserId)
                .targets(List.of(target.getUuidId()))
                .ruleId(new NotificationRuleId(UUID.randomUUID())) // to trigger user settings check
                .build();
        NotificationRequestStats stats = submitNotificationRequestAndWait(notificationRequest);
        assertThat(stats.getErrors()).isEmpty();
        assertThat(stats.getSent().get(NotificationDeliveryMethod.WEB).get()).isOne();

        notificationRequest.setTemplateId(entitiesLimitNotificationTemplate.getId());
        stats = submitNotificationRequestAndWait(notificationRequest);
        assertThat(stats.getSent().get(NotificationDeliveryMethod.WEB)).matches(n -> n == null || n.get() == 0);
        assertThat(stats.getErrors().get(NotificationDeliveryMethod.WEB).values()).first().asString().contains("disabled");

        notificationRequest.setTemplateId(apiUsageLimitNotificationTemplate.getId());
        stats = submitNotificationRequestAndWait(notificationRequest);
        assertThat(stats.getSent().get(NotificationDeliveryMethod.WEB)).matches(n -> n == null || n.get() == 0);
        assertThat(stats.getErrors().get(NotificationDeliveryMethod.WEB).values()).first().asString().contains("disabled");
    }

    @Test
    public void testSlackNotifications() throws Exception {
        NotificationSettings settings = new NotificationSettings();
        SlackNotificationDeliveryMethodConfig slackConfig = new SlackNotificationDeliveryMethodConfig();
        String slackToken = "xoxb-123123123";
        slackConfig.setBotToken(slackToken);
        settings.setDeliveryMethodsConfigs(Map.of(
                NotificationDeliveryMethod.SLACK, slackConfig
        ));
        saveNotificationSettings(settings);

        NotificationTemplate notificationTemplate = new NotificationTemplate();
        notificationTemplate.setName("Slack notification template");
        notificationTemplate.setNotificationType(NotificationType.GENERAL);
        NotificationTemplateConfig config = new NotificationTemplateConfig();
        SlackDeliveryMethodNotificationTemplate slackNotificationTemplate = new SlackDeliveryMethodNotificationTemplate();
        slackNotificationTemplate.setEnabled(true);
        slackNotificationTemplate.setBody("To Slack :)");
        config.setDeliveryMethodsTemplates(Map.of(
                NotificationDeliveryMethod.SLACK, slackNotificationTemplate
        ));
        notificationTemplate.setConfiguration(config);
        notificationTemplate = saveNotificationTemplate(notificationTemplate);

        String conversationId = "U154475415";
        String conversationName = "#my-channel";
        NotificationTarget notificationTarget = new NotificationTarget();
        notificationTarget.setTenantId(tenantId);
        notificationTarget.setName(conversationName + " in Slack");
        SlackNotificationTargetConfig targetConfig = new SlackNotificationTargetConfig();
        targetConfig.setConversation(SlackConversation.builder()
                .type(SlackConversationType.DIRECT)
                .id(conversationId)
                .name(conversationName)
                .build());
        notificationTarget.setConfiguration(targetConfig);
        notificationTarget = saveNotificationTarget(notificationTarget);

        NotificationRequest successfulNotificationRequest = submitNotificationRequest(List.of(notificationTarget.getId()), notificationTemplate.getId(), 0);
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> findNotificationRequest(successfulNotificationRequest.getId()).isSent());
        verify(slackService).sendMessage(eq(tenantId), eq(slackToken), eq(conversationId), eq(slackNotificationTemplate.getBody()));
        NotificationRequestStats stats = getStats(successfulNotificationRequest.getId());
        assertThat(stats.getSent().get(NotificationDeliveryMethod.SLACK)).hasValue(1);

        String errorMessage = "Error!!!";
        doThrow(new RuntimeException(errorMessage)).when(slackService).sendMessage(any(), any(), any(), any());
        NotificationRequest failedNotificationRequest = submitNotificationRequest(List.of(notificationTarget.getId()), notificationTemplate.getId(), 0);
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> findNotificationRequest(failedNotificationRequest.getId()).isSent());
        stats = getStats(failedNotificationRequest.getId());
        assertThat(stats.getErrors().get(NotificationDeliveryMethod.SLACK).values()).containsExactly(errorMessage);
    }

    @Test
    public void testInternalGeneralWebNotifications() throws Exception {
        loginSysAdmin();
        getAnotherWsClient().subscribeForUnreadNotifications(10).waitForReply(true);

        getAnotherWsClient().registerWaitForUpdate();

        DefaultNotifications.DefaultNotification expectedNotification = DefaultNotifications.maintenanceWork;
        notificationCenter.sendGeneralWebNotification(TenantId.SYS_TENANT_ID, new SystemAdministratorsFilter(),
                expectedNotification.toTemplate());

        getAnotherWsClient().waitForUpdate(true);
        Notification notification = getAnotherWsClient().getLastDataUpdate().getUpdate();
        assertThat(notification.getSubject()).isEqualTo(expectedNotification.getSubject());
        assertThat(notification.getText()).isEqualTo(expectedNotification.getText());
    }

    @Test
    public void testMicrosoftTeamsNotifications() throws Exception {
        RestTemplate restTemplate = mock(RestTemplate.class);
        microsoftTeamsNotificationChannel.setRestTemplate(restTemplate);

        String webhookUrl = "https://webhook.com/webhookb2/9628fa60-d873-11ed-913c-a196b1f9b445";
        var targetConfig = new MicrosoftTeamsNotificationTargetConfig();
        targetConfig.setWebhookUrl(webhookUrl);
        targetConfig.setChannelName("My channel");
        NotificationTarget target = new NotificationTarget();
        target.setName("Microsoft Teams channel");
        target.setConfiguration(targetConfig);
        target = saveNotificationTarget(target);

        var template = new MicrosoftTeamsDeliveryMethodNotificationTemplate();
        template.setEnabled(true);
        String templateParams = "${recipientTitle} - ${entityType}";
        template.setSubject("Subject: " + templateParams);
        template.setBody("Body: " + templateParams);
        template.setThemeColor("ff0000");
        var button = new MicrosoftTeamsDeliveryMethodNotificationTemplate.Button();
        button.setEnabled(true);
        button.setText("Button: " + templateParams);
        button.setLinkType(LinkType.LINK);
        button.setLink("https://" + templateParams);
        template.setButton(button);
        NotificationTemplate notificationTemplate = new NotificationTemplate();
        notificationTemplate.setName("Notification to Teams");
        notificationTemplate.setNotificationType(NotificationType.GENERAL);
        NotificationTemplateConfig templateConfig = new NotificationTemplateConfig();
        templateConfig.setDeliveryMethodsTemplates(Map.of(
                NotificationDeliveryMethod.MICROSOFT_TEAMS, template
        ));
        notificationTemplate.setConfiguration(templateConfig);
        notificationTemplate = saveNotificationTemplate(notificationTemplate);

        NotificationRequest notificationRequest = NotificationRequest.builder()
                .tenantId(tenantId)
                .originatorEntityId(tenantAdminUserId)
                .templateId(notificationTemplate.getId())
                .targets(List.of(target.getUuidId()))
                .info(EntityActionNotificationInfo.builder()
                        .entityId(new DeviceId(UUID.randomUUID()))
                        .actionType(ActionType.ADDED)
                        .userId(tenantAdminUserId.getId())
                        .build())
                .build();

        NotificationRequestPreview preview = doPost("/api/notification/request/preview", notificationRequest, NotificationRequestPreview.class);
        assertThat(preview.getRecipientsCountByTarget().get(target.getName())).isEqualTo(1);
        assertThat(preview.getRecipientsPreview()).containsOnly(targetConfig.getChannelName());

        var messageCaptor = ArgumentCaptor.forClass(MicrosoftTeamsNotificationChannel.Message.class);
        notificationCenter.processNotificationRequest(tenantId, notificationRequest, null);
        verify(restTemplate, timeout(20000)).postForEntity(eq(webhookUrl), messageCaptor.capture(), any());

        var message = messageCaptor.getValue();
        String expectedParams = "My channel - Device";
        assertThat(message.getThemeColor()).isEqualTo(template.getThemeColor());
        assertThat(message.getSections().get(0).getActivityTitle()).isEqualTo("Subject: " + expectedParams);
        assertThat(message.getSections().get(0).getActivitySubtitle()).isEqualTo("Body: " + expectedParams);
        assertThat(message.getPotentialAction().get(0).getName()).isEqualTo("Button: " + expectedParams);
        assertThat(message.getPotentialAction().get(0).getTargets().get(0).getUri()).isEqualTo("https://" + expectedParams);
    }

    private NotificationRequestStats submitNotificationRequestAndWait(NotificationRequest notificationRequest) throws Exception {
        SettableFuture<NotificationRequestStats> future = SettableFuture.create();
        notificationCenter.processNotificationRequest(notificationRequest.getTenantId(), notificationRequest, new FutureCallback<>() {
            @Override
            public void onSuccess(NotificationRequestStats result) {
                future.set(result);
            }

            @Override
            public void onFailure(Throwable t) {
                future.setException(t);
            }
        });
        return future.get(30, TimeUnit.SECONDS);
    }

    private NotificationRequestStats awaitNotificationRequest(NotificationRequestId requestId) {
        return await().atMost(30, TimeUnit.SECONDS)
                .until(() -> getStats(requestId), Objects::nonNull);
    }

    private void checkFullNotificationsUpdate(UnreadNotificationsUpdate notificationsUpdate, String... expectedNotifications) {
        assertThat(notificationsUpdate.getNotifications()).extracting(Notification::getText).containsOnly(expectedNotifications);
        assertThat(notificationsUpdate.getNotifications()).extracting(Notification::getType).containsOnly(DEFAULT_NOTIFICATION_TYPE);
        assertThat(notificationsUpdate.getNotifications()).extracting(Notification::getSubject).containsOnly(DEFAULT_NOTIFICATION_SUBJECT);
        assertThat(notificationsUpdate.getTotalUnreadCount()).isEqualTo(expectedNotifications.length);
    }

    private void checkPartialNotificationsUpdate(UnreadNotificationsUpdate notificationsUpdate, String expectedNotification, int expectedUnreadCount) {
        assertThat(notificationsUpdate.getUpdate()).extracting(Notification::getText).isEqualTo(expectedNotification);
        assertThat(notificationsUpdate.getUpdate()).extracting(Notification::getType).isEqualTo(DEFAULT_NOTIFICATION_TYPE);
        assertThat(notificationsUpdate.getUpdate()).extracting(Notification::getSubject).isEqualTo(DEFAULT_NOTIFICATION_SUBJECT);
        assertThat(notificationsUpdate.getTotalUnreadCount()).isEqualTo(expectedUnreadCount);
    }

    protected void connectOtherWsClient() throws Exception {
        loginCustomerUser();
        otherWsClient = super.getAnotherWsClient();
        loginTenantAdmin();
    }

}
