package org.keycloak.services.managers;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionModel;
import org.keycloak.models.Constants;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ModelException;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.session.UserSessionPersisterProvider;

/**
 *
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class UserSessionManager {

    protected static Logger logger = Logger.getLogger(UserSessionManager.class);

    private final KeycloakSession kcSession;
    private final UserSessionPersisterProvider persister;

    public UserSessionManager(KeycloakSession session) {
        this.kcSession = session;
        this.persister = session.getProvider(UserSessionPersisterProvider.class);
    }

    public void persistOfflineSession(ClientSessionModel clientSession, UserSessionModel userSession) {
        UserModel user = userSession.getUser();

        // Verify if we already have UserSession with this ID. If yes, don't create another one
        UserSessionModel offlineUserSession = kcSession.sessions().getOfflineUserSession(clientSession.getRealm(), userSession.getId());
        if (offlineUserSession == null) {
            offlineUserSession = createOfflineUserSession(user, userSession);
        }

        // Create clientSession and save to DB.
        createOfflineClientSession(user, clientSession, offlineUserSession);
    }

    // userSessionId is provided from offline token. It's used just to verify if it match the ID from clientSession representation
    public ClientSessionModel findOfflineClientSession(RealmModel realm, String clientSessionId, String userSessionId) {
        ClientSessionModel clientSession = kcSession.sessions().getOfflineClientSession(realm, clientSessionId);
        if (clientSession == null) {
            return null;
        }

        if (!userSessionId.equals(clientSession.getUserSession().getId())) {
            throw new ModelException("User session don't match. Offline client session " + clientSession.getId() + ", It's user session " + clientSession.getUserSession().getId() +
                    "  Wanted user session: " + userSessionId);
        }

        return clientSession;
    }

    public Set<ClientModel> findClientsWithOfflineToken(RealmModel realm, UserModel user) {
        List<ClientSessionModel> clientSessions = kcSession.sessions().getOfflineClientSessions(realm, user);
        Set<ClientModel> clients = new HashSet<>();
        for (ClientSessionModel clientSession : clientSessions) {
            clients.add(clientSession.getClient());
        }
        return clients;
    }

    public boolean revokeOfflineToken(UserModel user, ClientModel client) {
        RealmModel realm = client.getRealm();

        List<ClientSessionModel> clientSessions = kcSession.sessions().getOfflineClientSessions(realm, user);
        boolean anyRemoved = false;
        for (ClientSessionModel clientSession : clientSessions) {
            if (clientSession.getClient().getId().equals(client.getId())) {
                if (logger.isTraceEnabled()) {
                    logger.tracef("Removing existing offline token for user '%s' and client '%s' . ClientSessionID was '%s' .",
                            user.getUsername(), client.getClientId(), clientSession.getId());
                }

                kcSession.sessions().removeOfflineClientSession(realm, clientSession.getId());
                persister.removeClientSession(clientSession.getId(), true);
                checkOfflineUserSessionHasClientSessions(realm, user, clientSession.getUserSession().getId(), clientSessions);
                anyRemoved = true;
            }
        }

        return anyRemoved;
    }

    public boolean isOfflineTokenAllowed(ClientSessionModel clientSession) {
        RoleModel offlineAccessRole = clientSession.getRealm().getRole(Constants.OFFLINE_ACCESS_ROLE);
        if (offlineAccessRole == null) {
            logger.warnf("Role '%s' not available in realm", Constants.OFFLINE_ACCESS_ROLE);
            return false;
        }

        return clientSession.getRoles().contains(offlineAccessRole.getId());
    }

    private UserSessionModel createOfflineUserSession(UserModel user, UserSessionModel userSession) {
        if (logger.isTraceEnabled()) {
            logger.tracef("Creating new offline user session. UserSessionID: '%s' , Username: '%s'", userSession.getId(), user.getUsername());
        }

        UserSessionModel offlineUserSession = kcSession.sessions().createOfflineUserSession(userSession);
        persister.createUserSession(userSession, true);
        return offlineUserSession;
    }

    private void createOfflineClientSession(UserModel user, ClientSessionModel clientSession, UserSessionModel userSession) {
        if (logger.isTraceEnabled()) {
            logger.tracef("Creating new offline token client session. ClientSessionId: '%s', UserSessionID: '%s' , Username: '%s', Client: '%s'" ,
                    clientSession.getId(), userSession.getId(), user.getUsername(), clientSession.getClient().getClientId());
        }

        ClientSessionModel offlineClientSession = kcSession.sessions().createOfflineClientSession(clientSession);
        offlineClientSession.setUserSession(userSession);
        persister.createClientSession(clientSession, true);
    }

    // Check if userSession has any offline clientSessions attached to it. Remove userSession if not
    private void checkOfflineUserSessionHasClientSessions(RealmModel realm, UserModel user, String userSessionId, List<ClientSessionModel> clientSessions) {
        for (ClientSessionModel clientSession : clientSessions) {
            if (clientSession.getUserSession().getId().equals(userSessionId)) {
                return;
            }
        }

        if (logger.isTraceEnabled()) {
            logger.tracef("Removing offline userSession for user %s as it doesn't have any client sessions attached. UserSessionID: %s", user.getUsername(), userSessionId);
        }
        kcSession.sessions().removeOfflineUserSession(realm, userSessionId);
        persister.removeUserSession(userSessionId, true);
    }
}
