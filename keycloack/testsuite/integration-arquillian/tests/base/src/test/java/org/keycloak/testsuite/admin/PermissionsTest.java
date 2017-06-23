/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.testsuite.admin;

import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.AuthorizationResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.models.AdminRoles;
import org.keycloak.models.Constants;
import org.keycloak.representations.KeyStoreConfig;
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation;
import org.keycloak.representations.idm.AuthenticationExecutionRepresentation;
import org.keycloak.representations.idm.AuthenticationFlowRepresentation;
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation;
import org.keycloak.representations.idm.ClientInitialAccessCreatePresentation;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientTemplateRepresentation;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.IdentityProviderMapperRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.PartialImportRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;
import org.keycloak.representations.idm.RealmEventsConfigRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderRepresentation;
import org.keycloak.representations.idm.RequiredActionProviderSimpleRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.idm.authorization.PolicyRepresentation;
import org.keycloak.representations.idm.authorization.ResourceRepresentation;
import org.keycloak.representations.idm.authorization.ResourceServerRepresentation;
import org.keycloak.representations.idm.authorization.ScopeRepresentation;
import org.keycloak.services.resources.admin.RealmAuth.Resource;
import org.keycloak.testsuite.AbstractKeycloakTest;
import org.keycloak.testsuite.Assert;
import org.keycloak.testsuite.arquillian.AuthServerTestEnricher;
import org.keycloak.testsuite.util.AdminClientUtil;
import org.keycloak.testsuite.util.ClientBuilder;
import org.keycloak.testsuite.util.CredentialBuilder;
import org.keycloak.testsuite.util.FederatedIdentityBuilder;
import org.keycloak.testsuite.util.GreenMailRule;
import org.keycloak.testsuite.util.IdentityProviderBuilder;
import org.keycloak.testsuite.util.RealmBuilder;
import org.keycloak.testsuite.util.UserBuilder;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.keycloak.services.resources.admin.RealmAuth.Resource.AUTHORIZATION;
import static org.keycloak.services.resources.admin.RealmAuth.Resource.CLIENT;
import org.keycloak.testsuite.ProfileAssume;

/**
 * @author <a href="mailto:sthorger@redhat.com">Stian Thorgersen</a>
 */
public class PermissionsTest extends AbstractKeycloakTest {

    private static final String REALM_NAME = "permissions-test";

    private Map<String, Keycloak> clients = new HashMap<>();

    @Rule public GreenMailRule greenMailRule = new GreenMailRule();

    @Override
    public void addTestRealms(List<RealmRepresentation> testRealms) {
        RealmBuilder builder = RealmBuilder.create().name(REALM_NAME).testMail();
        builder.client(ClientBuilder.create().clientId("test-client").publicClient().directAccessGrants());

        builder.user(UserBuilder.create()
                .username(AdminRoles.REALM_ADMIN)
                .role(Constants.REALM_MANAGEMENT_CLIENT_ID, AdminRoles.REALM_ADMIN)
                .addPassword("password"));

        builder.user(UserBuilder.create().username("none").addPassword("password"));

        for (String role : AdminRoles.ALL_REALM_ROLES) {
            builder.user(UserBuilder.create().username(role).role(Constants.REALM_MANAGEMENT_CLIENT_ID, role).addPassword("password"));
        }
        testRealms.add(builder.build());

        RealmBuilder builder2 = RealmBuilder.create().name("realm2");
        builder2.client(ClientBuilder.create().clientId("test-client").publicClient().directAccessGrants());
        builder2.user(UserBuilder.create().username("admin").role(Constants.REALM_MANAGEMENT_CLIENT_ID, AdminRoles.REALM_ADMIN).addPassword("password"));
        testRealms.add(builder2.build());
    }


    @Before
    public void beforeClazz() {
        if (testContext.isInitialized()) {
            return;
        }

        createTestUsers();

        testContext.setInitialized(true);
    }

    private void createTestUsers() {
        RealmResource master = adminClient.realm("master");

        Response response = master.users().create(UserBuilder.create().username("permissions-test-master-none").build());
        String userId = ApiUtil.getCreatedId(response);
        response.close();
        master.users().get(userId).resetPassword(CredentialBuilder.create().password("password").build());

        for (String role : AdminRoles.ALL_REALM_ROLES) {
            response = master.users().create(UserBuilder.create().username("permissions-test-master-" + role).build());
            userId = ApiUtil.getCreatedId(response);
            response.close();

            master.users().get(userId).resetPassword(CredentialBuilder.create().password("password").build());
            String clientId = master.clients().findByClientId(REALM_NAME + "-realm").get(0).getId();
            RoleRepresentation roleRep = master.clients().get(clientId).roles().get(role).toRepresentation();
            master.users().get(userId).roles().clientLevel(clientId).add(Collections.singletonList(roleRep));
        }
    }

    @AfterClass
    public static void removeTestUsers() throws Exception {
        Keycloak adminClient = AdminClientUtil.createAdminClient();
        try {
            for (UserRepresentation u : adminClient.realm("master").users().search("permissions-test-master-", 0, 100)) {
                adminClient.realm("master").users().get(u.getId()).remove();
            }
        } finally {
            adminClient.close();
        }
    }

    private void recreatePermissionRealm() throws Exception {
        RealmRepresentation permissionRealm = testContext.getTestRealmReps().stream().filter(realm -> {
            return realm.getRealm().equals(REALM_NAME);
        }).findFirst().get();
        adminClient.realms().create(permissionRealm);

        removeTestUsers();
        createTestUsers();
    }


    @Override
    public void beforeAbstractKeycloakTest() throws Exception {
        super.beforeAbstractKeycloakTest();

        clients.put(AdminRoles.REALM_ADMIN,
                Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", REALM_NAME, AdminRoles.REALM_ADMIN, "password", "test-client",
                        "secret"));

        clients.put("none",
                Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", REALM_NAME, "none", "password", "test-client", "secret"));

        for (String role : AdminRoles.ALL_REALM_ROLES) {
            clients.put(role, Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", REALM_NAME, role, "password", "test-client"));
        }

        clients.put("REALM2", Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", "realm2", "admin", "password", "test-client"));

        clients.put("master-admin", adminClient);

        clients.put("master-none",
                Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", "master", "permissions-test-master-none", "password",
                        Constants.ADMIN_CLI_CLIENT_ID));


        for (String role : AdminRoles.ALL_REALM_ROLES) {
            clients.put("master-" + role,
                    Keycloak.getInstance(AuthServerTestEnricher.getAuthServerContextRoot() + "/auth", "master", "permissions-test-master-" + role, "password",
                            Constants.ADMIN_CLI_CLIENT_ID));
        }
    }

    @Override
    public void afterAbstractKeycloakTest() {
        // Don't close the "main" adminClient, but all others yes
        clients.entrySet().stream().filter(entry -> {

            return !entry.getKey().equals("master-admin");

        }).forEach(consumer -> {

            consumer.getValue().close();

        });

        clients.clear();
    }

    @Test
    public void realms() throws Exception {
        // Check returned realms
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("master-none").realms().findAll();
            }
        }, clients.get("none"), false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("none").realms().findAll();
            }
        }, clients.get("none"), false);
        Assert.assertNames(clients.get("master-admin").realms().findAll(), "master", REALM_NAME, "realm2");
        Assert.assertNames(clients.get(AdminRoles.REALM_ADMIN).realms().findAll(), REALM_NAME);
        Assert.assertNames(clients.get("REALM2").realms().findAll(), "realm2");

        // Check realm only contains name if missing view realm permission
        List<RealmRepresentation> realms = clients.get(AdminRoles.VIEW_USERS).realms().findAll();
        Assert.assertNames(realms, REALM_NAME);
        assertGettersEmpty(realms.get(0));

        realms = clients.get(AdminRoles.VIEW_REALM).realms().findAll();
        Assert.assertNames(realms, REALM_NAME);
        assertNotNull(realms.get(0).getAccessTokenLifespan());

        // Check the same when access with users from 'master' realm
        realms = clients.get("master-" + AdminRoles.VIEW_USERS).realms().findAll();
        Assert.assertNames(realms, REALM_NAME);
        assertGettersEmpty(realms.get(0));

        realms = clients.get("master-" + AdminRoles.VIEW_REALM).realms().findAll();
        Assert.assertNames(realms, REALM_NAME);
        assertNotNull(realms.get(0).getAccessTokenLifespan());

        // Create realm
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("master-admin").realms().create(RealmBuilder.create().name("master").build());
            }
        }, adminClient, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("master-" + AdminRoles.MANAGE_USERS).realms().create(RealmBuilder.create().name("master").build());
            }
        }, adminClient, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get(AdminRoles.REALM_ADMIN).realms().create(RealmBuilder.create().name("master").build());
            }
        }, adminClient, false);

        // Get realm
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.toRepresentation();
            }
        }, Resource.REALM, false, true);
        assertGettersEmpty(clients.get(AdminRoles.VIEW_USERS).realm(REALM_NAME).toRepresentation());

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.update(new RealmRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.pushRevocation();
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.deleteSession("nosuch");
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getClientSessionStats();
            }
        }, Resource.REALM, false);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getDefaultGroups();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.addDefaultGroup("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.removeDefaultGroup("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getGroupByPath("nosuch");
            }
        }, Resource.REALM, false);

        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.testLDAPConnection("nosuch", "nosuch", "nosuch", "nosuch", "nosuch", "nosuch"));
            }
        }, Resource.REALM, true);

        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.partialImport(new PartialImportRepresentation()));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clearRealmCache();
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clearUserCache();
            }
        }, Resource.REALM, true);

        // Delete realm
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("master-admin").realms().realm("nosuch").remove();
            }
        }, adminClient, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get("REALM2").realms().realm(REALM_NAME).remove();
            }
        }, adminClient, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get(AdminRoles.MANAGE_USERS).realms().realm(REALM_NAME).remove();
            }
        }, adminClient, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                clients.get(AdminRoles.REALM_ADMIN).realms().realm(REALM_NAME).remove();
            }
        }, adminClient, true);

        // Revert realm removal
        recreatePermissionRealm();
    }

    @Test
    public void realmLogoutAll() {
        Invocation invocation = new Invocation() {
            public void invoke(RealmResource realm) {
                realm.logoutAll();
            }
        };

        invoke(invocation, clients.get("master-none"), false);
        invoke(invocation, clients.get("master-view-realm"), false);

        invoke(invocation, clients.get("REALM2"), false);

        invoke(invocation, clients.get("none"), false);
        invoke(invocation, clients.get("view-users"), false);
        invoke(invocation, clients.get("manage-realm"), false);
        invoke(invocation, clients.get("master-manage-realm"), false);

        invoke(invocation, clients.get("manage-users"), true);
        invoke(invocation, clients.get("master-manage-users"), true);
    }

    @Test
    public void events() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getRealmEventsConfig();
            }
        }, Resource.EVENTS, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.updateRealmEventsConfig(new RealmEventsConfigRepresentation());
            }
        }, Resource.EVENTS, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getEvents();
            }
        }, Resource.EVENTS, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.getAdminEvents();
            }
        }, Resource.EVENTS, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clearEvents();
            }
        }, Resource.EVENTS, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clearAdminEvents();
            }
        }, Resource.EVENTS, true);
    }

    @Test
    public void attackDetection() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.attackDetection().bruteForceUserStatus("nosuch");
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.attackDetection().clearBruteForceForUser("nosuch");
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.attackDetection().clearAllBruteForce();
            }
        }, Resource.USER, true);
    }

    @Test
    public void clients() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().findAll();
            }
        }, Resource.CLIENT, false, true);
        List<ClientRepresentation> l = clients.get(AdminRoles.VIEW_USERS).realm(REALM_NAME).clients().findAll();
        assertGettersEmpty(l.get(0));

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.convertClientDescription("blahblah");
            }
        }, Resource.CLIENT, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.clients().create(ClientBuilder.create().clientId("foo").build()));
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").toRepresentation();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getInstallationProvider("nosuch");
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").update(new ClientRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").remove();
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").generateNewSecret();
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").regenerateRegistrationAccessToken();
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getSecret();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getServiceAccountUser();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").pushRevocation();
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getApplicationSessionCount();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getUserSessions(0, 100);
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getOfflineSessionCount();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getOfflineUserSessions(0, 100);
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").registerNode(Collections.<String, String>emptyMap());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").unregisterNode("nosuch");
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").testNodesAvailable();
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").generate();
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").generateAndGetKeystore(new KeyStoreConfig());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").getKeyInfo();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").getKeystore(new KeyStoreConfig());
            }
        }, Resource.CLIENT, false);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").uploadJks(new MultipartFormDataOutput());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getCertficateResource("nosuch").uploadJksCertificate(new MultipartFormDataOutput());
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().createMapper(Collections.EMPTY_LIST);
            }
        }, Resource.CLIENT, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.clients().get("nosuch").getProtocolMappers().createMapper(new ProtocolMapperRepresentation()));
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().getMapperById("nosuch");
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().getMappers();
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().getMappersPerProtocol("nosuch");
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().update("nosuch", new ProtocolMapperRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getProtocolMappers().delete("nosuch");
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().getAll();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().realmLevel().listAll();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().realmLevel().listEffective();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().realmLevel().listAvailable();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().realmLevel().add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").getScopeMappings().realmLevel().remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().list();
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().create(new RoleRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").toRepresentation();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().deleteRole("nosuch");
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").update(new RoleRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").addComposites(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").deleteComposites(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").getRoleComposites();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").getRealmRoleComposites();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clients().get("nosuch").roles().get("nosuch").getClientRoleComposites("nosuch");
            }
        }, Resource.CLIENT, false);
    }

    @Test
    public void clientTemplates() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().findAll();
            }
        }, Resource.CLIENT, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.clientTemplates().create(new ClientTemplateRepresentation()));
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").toRepresentation();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").update(new ClientTemplateRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").remove();
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().getMappers();
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().getMappersPerProtocol("nosuch");
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().getMapperById("nosuch");
            }
        }, Resource.CLIENT, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().update("nosuch", new ProtocolMapperRepresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.clientTemplates().get("nosuch").getProtocolMappers().createMapper(new ProtocolMapperRepresentation()));
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().createMapper(Collections.<ProtocolMapperRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getProtocolMappers().delete("nosuch");
            }
        }, Resource.CLIENT, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().getAll();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().realmLevel().listAll();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().realmLevel().listAvailable();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().realmLevel().listEffective();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().realmLevel().add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().realmLevel().remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().clientLevel("nosuch").listAll();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().clientLevel("nosuch").listAvailable();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().clientLevel("nosuch").listEffective();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().clientLevel("nosuch").add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientTemplates().get("nosuch").getScopeMappings().clientLevel("nosuch").remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.CLIENT, true);
    }

    @Test
    public void clientInitialAccess() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientInitialAccess().list();
            }
        }, Resource.CLIENT, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientInitialAccess().create(new ClientInitialAccessCreatePresentation());
            }
        }, Resource.CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.clientInitialAccess().delete("nosuch");
            }
        }, Resource.CLIENT, true);
    }

    @Test
    public void clientAuthorization() {
        ProfileAssume.assumePreview();
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                realm.clients().create(ClientBuilder.create().clientId("foo-authz").build());
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                foo.setServiceAccountsEnabled(true);
                foo.setAuthorizationServicesEnabled(true);
                realm.clients().get(foo.getId()).update(foo);
            }
        }, CLIENT, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                realm.clients().get(foo.getId()).authorization().getSettings();
            }
        }, AUTHORIZATION, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                ResourceServerRepresentation settings = authorization.getSettings();
                authorization.update(settings);
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.resources().resources();
            }
        }, AUTHORIZATION, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.scopes().scopes();
            }
        }, AUTHORIZATION, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.policies().policies();
            }
        }, AUTHORIZATION, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                response.set(authorization.resources().create(new ResourceRepresentation("Test", Collections.emptySet())));
            }
        }, AUTHORIZATION, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                response.set(authorization.scopes().create(new ScopeRepresentation("Test")));
            }
        }, AUTHORIZATION, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                PolicyRepresentation representation = new PolicyRepresentation();
                representation.setName("Test PermissionsTest");
                representation.setType("js");
                HashMap<String, String> config = new HashMap<>();
                config.put("code", "");
                representation.setConfig(config);
                response.set(authorization.policies().create(representation));
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.resources().resource("nosuch").update(new ResourceRepresentation());
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.scopes().scope("nosuch").update(new ScopeRepresentation());
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.policies().policy("nosuch").update(new PolicyRepresentation());
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.resources().resource("nosuch").remove();
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.scopes().scope("nosuch").remove();
            }
        }, AUTHORIZATION, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                org.keycloak.representations.idm.ClientRepresentation foo = realm.clients().findByClientId("foo-authz").get(0);
                AuthorizationResource authorization = realm.clients().get(foo.getId()).authorization();
                authorization.policies().policy("nosuch").remove();
            }
        }, AUTHORIZATION, true);
    }

    @Test
    public void roles() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().list();
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").toRepresentation();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").update(new RoleRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().create(new RoleRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().deleteRole("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").getRoleComposites();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").addComposites(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").deleteComposites(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").getRoleComposites();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").getRealmRoleComposites();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.roles().get("nosuch").getClientRoleComposites("nosuch");
            }
        }, Resource.REALM, false);
    }

    @Test
    public void flows() throws Exception {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getFormProviders();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getAuthenticatorProviders();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getClientAuthenticatorProviders();
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getFormActionProviders();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getFlows();
            }
        }, Resource.REALM, false, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.flows().createFlow(new AuthenticationFlowRepresentation()));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getFlow("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().deleteFlow("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.flows().copy("nosuch", Collections.<String, String>emptyMap()));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().addExecutionFlow("nosuch", Collections.<String, String>emptyMap());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().addExecution("nosuch", Collections.<String, String>emptyMap());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getExecutions("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().updateExecutions("nosuch", new AuthenticationExecutionInfoRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                AuthenticationExecutionRepresentation rep = new AuthenticationExecutionRepresentation();
                rep.setAuthenticator("auth-cookie");
                rep.setRequirement("OPTIONAL");
                response.set(realm.flows().addExecution(rep));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().raisePriority("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().lowerPriority("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().removeExecution("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.flows().newExecutionConfig("nosuch", new AuthenticatorConfigRepresentation()));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getAuthenticatorConfig("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getUnregisteredRequiredActions();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().registerRequiredAction(new RequiredActionProviderSimpleRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getRequiredActions();
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getRequiredAction("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().removeRequiredAction("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().updateRequiredAction("nosuch", new RequiredActionProviderRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getAuthenticatorConfigDescription("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getPerClientConfigDescription();
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().getAuthenticatorConfig("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().removeAuthenticatorConfig("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.flows().updateAuthenticatorConfig("nosuch", new AuthenticatorConfigRepresentation());
            }
        }, Resource.REALM, true);

        // Re-create realm
        adminClient.realm(REALM_NAME).remove();

        recreatePermissionRealm();
    }

    @Test
    public void rolesById() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().getRole("nosuch");
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().updateRole("nosuch", new RoleRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().deleteRole("nosuch");
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().getRoleComposites("nosuch");
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().addComposites("nosuch", Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().deleteComposites("nosuch", Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().getRoleComposites("nosuch");
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().getRealmRoleComposites("nosuch");
            }
        }, Resource.REALM, false, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.rolesById().getClientRoleComposites("nosuch", "nosuch");
            }
        }, Resource.REALM, false, true);
    }

    @Test
    public void groups() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().groups();
            }
        }, Resource.USER, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                GroupRepresentation group = new GroupRepresentation();
                group.setName("mygroup");
                response.set(realm.groups().add(group));
            }
        }, Resource.USER, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").toRepresentation();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").update(new GroupRepresentation());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").remove();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").members(0, 100);
            }
        }, Resource.USER, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.groups().group("nosuch").subGroup(new GroupRepresentation()));
            }
        }, Resource.USER, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().getAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().realmLevel().listAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().realmLevel().listEffective();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().realmLevel().listAvailable();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().realmLevel().add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().realmLevel().remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().clientLevel("nosuch").listAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().clientLevel("nosuch").listEffective();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().clientLevel("nosuch").listAvailable();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().clientLevel("nosuch").add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.groups().group("nosuch").roles().clientLevel("nosuch").remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
    }

    // Permissions for impersonation tested in ImpersonationTest
    @Test
    public void users() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").toRepresentation();
            }
        }, Resource.USER, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.users().create(UserBuilder.create().username("testuser").build()));
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").update(UserBuilder.create().enabled(true).build());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().search("foo", 0, 1);
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().count();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").getUserSessions();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").getOfflineSessions("nosuch");
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").getFederatedIdentity();
            }
        }, Resource.USER, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.users()
                        .get("nosuch")
                        .addFederatedIdentity("nosuch",
                                FederatedIdentityBuilder.create().identityProvider("nosuch").userId("nosuch").userName("nosuch").build()));
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").removeFederatedIdentity("nosuch");
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").getConsents();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").revokeConsent("testclient");
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").logout();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").remove();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").resetPassword(CredentialBuilder.create().password("password").build());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").removeTotp();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").resetPasswordEmail();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").executeActionsEmail(Collections.<String>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").sendVerifyEmail();
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").groups();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").leaveGroup("nosuch");
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").joinGroup("nosuch");
            }
        }, Resource.USER, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().getAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().realmLevel().listAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().realmLevel().listAvailable();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().realmLevel().listEffective();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().realmLevel().add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().realmLevel().remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().clientLevel("nosuch").listAll();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().clientLevel("nosuch").listAvailable();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().clientLevel("nosuch").listEffective();
            }
        }, Resource.USER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().clientLevel("nosuch").add(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.users().get("nosuch").roles().clientLevel("nosuch").remove(Collections.<RoleRepresentation>emptyList());
            }
        }, Resource.USER, true);
    }

    @Test
    public void identityProviders() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().findAll();
            }
        }, Resource.IDENTITY_PROVIDER, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.identityProviders().create(IdentityProviderBuilder.create().providerId("nosuch")
                        .displayName("nosuch-foo").alias("foo").build()));
            }
        }, Resource.IDENTITY_PROVIDER, true);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").toRepresentation();
            }
        }, Resource.IDENTITY_PROVIDER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").update(new IdentityProviderRepresentation());
            }
        }, Resource.IDENTITY_PROVIDER, true);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.identityProviders().get("nosuch").export("saml"));
            }
        }, Resource.IDENTITY_PROVIDER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").remove();
            }
        }, Resource.IDENTITY_PROVIDER, true);

        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.identityProviders().get("nosuch").addMapper(new IdentityProviderMapperRepresentation()));
            }
        }, Resource.IDENTITY_PROVIDER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").delete("nosuch");
            }
        }, Resource.IDENTITY_PROVIDER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").getMappers();
            }
        }, Resource.IDENTITY_PROVIDER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").getMapperById("nosuch");
            }
        }, Resource.IDENTITY_PROVIDER, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().get("nosuch").getMapperTypes();
            }
        }, Resource.IDENTITY_PROVIDER, false);

        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().importFrom(Collections.<String, Object>emptyMap());
            }
        }, Resource.IDENTITY_PROVIDER, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.identityProviders().importFrom(new MultipartFormDataOutput());
            }
        }, Resource.IDENTITY_PROVIDER, true);
    }

    @Test
    public void components() {
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.components().query();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.components().query("nosuch");
            }
        }, Resource.REALM, false);
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                response.set(realm.components().add(new ComponentRepresentation()));
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.components().component("nosuch").toRepresentation();
            }
        }, Resource.REALM, false);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.components().component("nosuch").update(new ComponentRepresentation());
            }
        }, Resource.REALM, true);
        invoke(new Invocation() {
            public void invoke(RealmResource realm) {
                realm.components().component("nosuch").remove();
            }
        }, Resource.REALM, true);
    }

    private void invoke(final Invocation invocation, Resource resource, boolean manage) {
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                invocation.invoke(realm);
            }
        }, resource, manage);
    }

    private void invoke(final Invocation invocation, Resource resource, boolean manage, boolean skipDifferentRole) {
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                invocation.invoke(realm);
            }
        }, resource, manage, skipDifferentRole);
    }

    private void invoke(InvocationWithResponse invocation, Resource resource, boolean manage) {
        invoke(invocation, resource, manage, false);
    }

    private void invoke(InvocationWithResponse invocation, Resource resource, boolean manage, boolean skipDifferentRole) {
        String viewRole = getViewRole(resource);
        String manageRole = getManageRole(resource);
        String differentViewRole = getDifferentViewRole(resource);
        String differentManageRole = getDifferentManageRole(resource);

        invoke(invocation, clients.get("master-none"), false);
        invoke(invocation, clients.get("master-admin"), true);
        invoke(invocation, clients.get("master-" + viewRole), !manage);
        invoke(invocation, clients.get("master-" + manageRole), true);
        if (!skipDifferentRole) {
            invoke(invocation, clients.get("master-" + differentViewRole), false);
            invoke(invocation, clients.get("master-" + differentManageRole), false);
        }

        invoke(invocation, clients.get("none"), false);
        invoke(invocation, clients.get(AdminRoles.REALM_ADMIN), true);
        invoke(invocation, clients.get(viewRole), !manage);
        invoke(invocation, clients.get(manageRole), true);
        if (!skipDifferentRole) {
            invoke(invocation, clients.get(differentViewRole), false);
            invoke(invocation, clients.get(differentManageRole), false);
        }

        invoke(invocation, clients.get("REALM2"), false);
    }

    private void invoke(final Invocation invocation, Keycloak client, boolean expectSuccess) {
        invoke(new InvocationWithResponse() {
            public void invoke(RealmResource realm, AtomicReference<Response> response) {
                invocation.invoke(realm);
            }
        }, client, expectSuccess);
    }

    private void invoke(InvocationWithResponse invocation, Keycloak client, boolean expectSuccess) {
        int statusCode;
        try {
            AtomicReference<Response> responseReference = new AtomicReference<>();
            invocation.invoke(client.realm(REALM_NAME), responseReference);
            Response response = responseReference.get();
            if (response != null) {
                statusCode = response.getStatus();
            } else {
                // OK (we don't care about the exact status code
                statusCode = 200;
            }
        } catch (ClientErrorException e) {
            statusCode = e.getResponse().getStatus();
        }

        if (expectSuccess) {
            if (!(statusCode == 200 || statusCode == 201 || statusCode == 204 || statusCode == 404 || statusCode == 409 || statusCode == 400)) {
                fail("Expected permitted, but was " + statusCode);
            }
        } else {
            if (statusCode != 403) {
                fail("Expected 403, but was " + statusCode);
            }
        }
    }

    private String getViewRole(Resource resource) {
        switch (resource) {
            case CLIENT:
                return AdminRoles.VIEW_CLIENTS;
            case USER:
                return AdminRoles.VIEW_USERS;
            case REALM:
                return AdminRoles.VIEW_REALM;
            case EVENTS:
                return AdminRoles.VIEW_EVENTS;
            case IDENTITY_PROVIDER:
                return AdminRoles.VIEW_IDENTITY_PROVIDERS;
            case AUTHORIZATION:
                return AdminRoles.VIEW_AUTHORIZATION;
            default:
                throw new RuntimeException("Unexpected resouce");
        }
    }

    private String getManageRole(Resource resource) {
        switch (resource) {
            case CLIENT:
                return AdminRoles.MANAGE_CLIENTS;
            case USER:
                return AdminRoles.MANAGE_USERS;
            case REALM:
                return AdminRoles.MANAGE_REALM;
            case EVENTS:
                return AdminRoles.MANAGE_EVENTS;
            case IDENTITY_PROVIDER:
                return AdminRoles.MANAGE_IDENTITY_PROVIDERS;
            case AUTHORIZATION:
                return AdminRoles.MANAGE_AUTHORIZATION;
            default:
                throw new RuntimeException("Unexpected resouce");
        }
    }

    private String getDifferentViewRole(Resource resource) {
        switch (resource) {
            case CLIENT:
                return AdminRoles.VIEW_USERS;
            case USER:
                return AdminRoles.VIEW_CLIENTS;
            case REALM:
                return AdminRoles.VIEW_EVENTS;
            case EVENTS:
                return AdminRoles.VIEW_IDENTITY_PROVIDERS;
            case IDENTITY_PROVIDER:
                return AdminRoles.VIEW_REALM;
            case AUTHORIZATION:
                return AdminRoles.VIEW_IDENTITY_PROVIDERS;
            default:
                throw new RuntimeException("Unexpected resouce");
        }
    }

    private String getDifferentManageRole(Resource resource) {
        switch (resource) {
            case CLIENT:
                return AdminRoles.MANAGE_USERS;
            case USER:
                return AdminRoles.MANAGE_CLIENTS;
            case REALM:
                return AdminRoles.MANAGE_EVENTS;
            case EVENTS:
                return AdminRoles.MANAGE_IDENTITY_PROVIDERS;
            case IDENTITY_PROVIDER:
                return AdminRoles.MANAGE_REALM;
            case AUTHORIZATION:
                return AdminRoles.MANAGE_IDENTITY_PROVIDERS;
            default:
                throw new RuntimeException("Unexpected resouce");
        }
    }

    public interface Invocation {

        void invoke(RealmResource realm);

    }

    public interface InvocationWithResponse {

        void invoke(RealmResource realm, AtomicReference<Response> response);

    }

    private void assertGettersEmpty(RealmRepresentation rep) {
        assertGettersEmpty(rep, "getRealm");
    }

    private void assertGettersEmpty(ClientRepresentation rep) {
        assertGettersEmpty(rep, "getId", "getClientId", "getDescription");
    }

    private void assertGettersEmpty(Object rep, String... ignore) {
        List<String> ignoreList = Arrays.asList(ignore);

        for (Method m : rep.getClass().getDeclaredMethods()) {
            if (m.getParameters().length == 0 && m.getName().startsWith("get") && !ignoreList.contains(m.getName())) {
                try {
                    Object o = m.invoke(rep);
                    assertNull("Expected " + m.getName() + " to be null", o);
                } catch (Exception e) {
                    fail(e.getMessage());
                }
            }
        }
    }

}