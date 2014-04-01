/* Copyright 2004, 2005, 2006 Acegi Technology Pty Limited
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

package org.sipfoundry.sipxconfig.security;

import static org.sipfoundry.commons.security.Util.retrieveDomain;
import static org.sipfoundry.commons.security.Util.retrieveUsername;

import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.sipfoundry.sipxconfig.common.User;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.AbstractUserDetailsAuthenticationProvider;
import org.springframework.security.authentication.dao.SaltSource;
import org.springframework.security.authentication.encoding.PasswordEncoder;
import org.springframework.security.authentication.encoding.PlaintextPasswordEncoder;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.util.Assert;

/**
 * An {@link AuthenticationProvider} implementation that retrieves user details
 * from an {@link UserDetailsService}.
 *
 * @author Ben Alex
 * @version $Id: DaoAuthenticationProvider.java 1857 2007-05-24 00:47:12Z
 * benalex $
 *
 * Code copied from org.acegisecurity.providers.dao.DaoAuthenticationProvider
 */
public class DaoAuthenticationProvider extends AbstractUserDetailsAuthenticationProvider {

    // ~ Instance fields
    // ================================================================================================

    private static final Log LOG = LogFactory.getLog(DaoAuthenticationProvider.class);

    private PasswordEncoder passwordEncoder = new PlaintextPasswordEncoder();

    private SaltSource saltSource;

    private UserDetailsService userDetailsService;

    private SystemAuthPolicyCollectorImpl m_systemAuthPolicyCollector;

    private boolean includeDetailsObject = true;

    // ~ Methods
    // ========================================================================================================

    @Override
    protected void additionalAuthenticationChecks(UserDetails userDetails,
            UsernamePasswordAuthenticationToken authentication) throws AuthenticationException {
        Object salt = null;

        if (this.saltSource != null) {
            salt = this.saltSource.getSalt(userDetails);
        }

        if (authentication.getCredentials() == null) {
            throw new BadCredentialsException(messages.getMessage(
                    "AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"),
                    includeDetailsObject ? userDetails : null);
        }

        String presentedPassword = authentication.getCredentials() == null ? "" : authentication.getCredentials()
                .toString();

        if (!passwordEncoder.isPasswordValid(userDetails.getPassword(), presentedPassword, salt)) {
            throw new BadCredentialsException(messages.getMessage(
                    "AbstractUserDetailsAuthenticationProvider.badCredentials", "Bad credentials"),
                    includeDetailsObject ? userDetails : null);
        }
    }

    @Override
    protected void doAfterPropertiesSet() throws Exception {
        Assert.notNull(this.userDetailsService, "A UserDetailsService must be set");
    }

    public PasswordEncoder getPasswordEncoder() {
        return passwordEncoder;
    }

    public SaltSource getSaltSource() {
        return saltSource;
    }

    public UserDetailsService getUserDetailsService() {
        return userDetailsService;
    }

    @Override
    protected final UserDetails retrieveUser(String username, UsernamePasswordAuthenticationToken authentication)
            throws AuthenticationException {

        UserDetailsImpl loadedUser = null;

        String userLoginName = retrieveUsername(username);
        String domain = retrieveDomain(username);
        try {
            loadedUser = (UserDetailsImpl)getUserDetailsService().loadUserByUsername(userLoginName);
        }
        catch (DuplicateUserException dupEx) {
            if (StringUtils.isEmpty(domain)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Duplicate user exception: " + dupEx.getMessage());
                }
                throw new AuthenticationServiceException(dupEx.getMessage());
            } else {
                List<User> users = dupEx.getUsers();
                boolean loaded = false;
                for (User user : users) {
                    if (loaded && StringUtils.equals(user.getUserDomain(), domain)) {
                        String message = "duplicate user and domain";
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("Duplicate user exception: " + message);
                        }
                        throw new AuthenticationServiceException(message);
                    }
                    if (!loaded && StringUtils.equals(user.getUserDomain(), domain)) {
                        loadedUser = (UserDetailsImpl) ((AbstractUserDetailsService) getUserDetailsService()).
                            createUserDetails(userLoginName, user);
                        loaded = true;
                    }
                }
            }
        }
        catch (DataAccessException repositoryProblem) {
            throw new AuthenticationServiceException(repositoryProblem.getMessage(), repositoryProblem);
        }

        if (loadedUser == null) {
            String message = "UserDetailsService returned null, which is an interface contract violation";
            if (LOG.isDebugEnabled()) {
                LOG.debug(message);
            }
            throw new AuthenticationServiceException(message);
        }

        // all admin users should be able to login with sipXecs password even if LDAP only
        // some non-admin are configured to authenticate to db only - policy verification is not needed
        if (!loadedUser.isAdmin() && !loadedUser.isDbAuthOnly()) {
            m_systemAuthPolicyCollector.verifyPolicy(username);
        }

        if (domain != null && !StringUtils.equals(loadedUser.getUserDomain(), domain)) {
            String message = "The following domain does not belong to the actual user: " + domain
                + " in the system - is an interface contract violation";
            if (LOG.isDebugEnabled()) {
                LOG.debug(message);
            }
            throw new AuthenticationServiceException(message);
        }
        return loadedUser;
    }

    /**
     * Sets the PasswordEncoder instance to be used to encode and validate
     * passwords. If not set, {@link PlaintextPasswordEncoder} will be used by
     * default.
     *
     * @param passwordEncoder The passwordEncoder to use
     */
    public void setPasswordEncoder(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * The source of salts to use when decoding passwords. <code>null</code>
     * is a valid value, meaning the <code>DaoAuthenticationProvider</code>
     * will present <code>null</code> to the relevant
     * <code>PasswordEncoder</code>.
     *
     * @param saltSource to use when attempting to decode passwords via the
     * <code>PasswordEncoder</code>
     */
    public void setSaltSource(SaltSource saltSource) {
        this.saltSource = saltSource;
    }

    public void setUserDetailsService(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    public boolean isIncludeDetailsObject() {
        return includeDetailsObject;
    }

    public void setIncludeDetailsObject(boolean includeDetailsObject) {
        this.includeDetailsObject = includeDetailsObject;
    }

    public void setSystemAuthPolicyCollector(SystemAuthPolicyCollectorImpl systemAuthPolicyCollector) {
        m_systemAuthPolicyCollector = systemAuthPolicyCollector;
    }
}
