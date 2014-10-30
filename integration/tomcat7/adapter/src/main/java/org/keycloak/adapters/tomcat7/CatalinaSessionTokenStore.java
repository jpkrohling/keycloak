package org.keycloak.adapters.tomcat7;

import java.util.Set;
import java.util.logging.Logger;

import org.apache.catalina.Session;
import org.apache.catalina.connector.Request;
import org.apache.catalina.realm.GenericPrincipal;
import org.keycloak.KeycloakSecurityContext;
import org.keycloak.adapters.AdapterTokenStore;
import org.keycloak.adapters.KeycloakAccount;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.RefreshableKeycloakSecurityContext;
import org.keycloak.adapters.RequestAuthenticator;

/**
 * @author <a href="mailto:mposolda@redhat.com">Marek Posolda</a>
 */
public class CatalinaSessionTokenStore implements AdapterTokenStore {

    private static final Logger log = Logger.getLogger(""+CatalinaSessionTokenStore.class);

    private Request request;
    private KeycloakDeployment deployment;
    private CatalinaUserSessionManagement sessionManagement;

    public CatalinaSessionTokenStore(Request request, KeycloakDeployment deployment, CatalinaUserSessionManagement sessionManagement) {
        this.request = request;
        this.deployment = deployment;
        this.sessionManagement = sessionManagement;
    }

    @Override
    public void checkCurrentToken() {
        if (request.getSessionInternal(false) == null || request.getSessionInternal().getPrincipal() == null) return;
        RefreshableKeycloakSecurityContext session = (RefreshableKeycloakSecurityContext) request.getSessionInternal().getNote(KeycloakSecurityContext.class.getName());
        if (session == null) return;

        if (!deployment.getRealm().equals(session.getRealm())) {
            log.fine("Account from cookie is from a different realm than for the request.");
            return;
        }

        // just in case session got serialized
        if (session.getDeployment() == null) session.setCurrentRequestInfo(deployment, this);

        if (session.isActive() && !session.getDeployment().isAlwaysRefreshToken()) return;

        // FYI: A refresh requires same scope, so same roles will be set.  Otherwise, refresh will fail and token will
        // not be updated
        boolean success = session.refreshExpiredToken(false);
        if (success && session.isActive()) return;

        // Refresh failed, so user is already logged out from keycloak. Cleanup and expire our session
        Session catalinaSession = request.getSessionInternal();
        log.fine("Cleanup and expire session " + catalinaSession.getId() + " after failed refresh");
        catalinaSession.removeNote(KeycloakSecurityContext.class.getName());
        request.setUserPrincipal(null);
        request.setAuthType(null);
        catalinaSession.setPrincipal(null);
        catalinaSession.setAuthType(null);
        catalinaSession.expire();
    }

    @Override
    public boolean isCached(RequestAuthenticator authenticator) {
        if (request.getSessionInternal(false) == null || request.getSessionInternal().getPrincipal() == null)
            return false;
        log.fine("remote logged in already. Establish state from session");
        GenericPrincipal principal = (GenericPrincipal) request.getSessionInternal().getPrincipal();
        request.setUserPrincipal(principal);
        request.setAuthType("KEYCLOAK");

        RefreshableKeycloakSecurityContext securityContext = (RefreshableKeycloakSecurityContext) request.getSessionInternal().getNote(KeycloakSecurityContext.class.getName());
        if (securityContext != null) {
            securityContext.setCurrentRequestInfo(deployment, this);
            request.setAttribute(KeycloakSecurityContext.class.getName(), securityContext);
        }

        ((CatalinaRequestAuthenticator)authenticator).restoreRequest();
        return true;
    }

    @Override
    public void saveAccountInfo(KeycloakAccount account) {
        RefreshableKeycloakSecurityContext securityContext = (RefreshableKeycloakSecurityContext)account.getKeycloakSecurityContext();
        Set<String> roles = account.getRoles();
        GenericPrincipal principal = new CatalinaSecurityContextHelper().createPrincipal(request.getContext().getRealm(), account.getPrincipal(), roles, securityContext);

        Session session = request.getSessionInternal(true);
        session.setPrincipal(principal);
        session.setAuthType("OAUTH");
        session.setNote(KeycloakSecurityContext.class.getName(), securityContext);
        String username = securityContext.getToken().getSubject();
        log.fine("userSessionManagement.login: " + username);
        this.sessionManagement.login(session);
    }

    @Override
    public void logout() {
        Session session = request.getSessionInternal(false);
        if (session != null) {
            session.removeNote(KeycloakSecurityContext.class.getName());
            KeycloakSecurityContext ksc = (KeycloakSecurityContext)request.getAttribute(KeycloakSecurityContext.class.getName());
            if (ksc instanceof RefreshableKeycloakSecurityContext) {
                ((RefreshableKeycloakSecurityContext) ksc).logout(deployment);
            }
        }
    }

    @Override
    public void refreshCallback(RefreshableKeycloakSecurityContext securityContext) {
        // no-op
    }
}
