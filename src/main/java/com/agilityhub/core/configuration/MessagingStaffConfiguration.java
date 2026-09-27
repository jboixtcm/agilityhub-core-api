package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.messaging.application.ports.MemberContact;
import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.StaffDirectoryPort;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionService;
import com.agilityhub.core.identity.application.SignupIdentityService;
import com.agilityhub.core.shared.application.NotificationAccounts;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * E7-T02 step 1, the real {@link StaffDirectoryPort} at the composition root: the S11 staff audiences need four owners —
 * the ADMIN memberships (identity), the instructors (catalogs), a class's `instructorIds` (scheduling) and the contact data
 * (census, through {@link MemberDirectoryPort}) — and no single context may import the other three without a cycle. Only
 * composition here. An administrator without a member record in the club is addressed at the account's e-mail, in the
 * account's language.
 */
@Configuration(proxyBeanMethods = false)
public class MessagingStaffConfiguration {
    @Bean StaffDirectoryPort staffDirectory(SignupIdentityService identities, PlanningCatalogAccess catalogs, ClassSessionService sessions,
            MemberDirectoryPort members, NotificationAccounts accounts) {
        return new StaffDirectoryPort() {
            @Override public List<MemberContact> admins() {
                var result = new ArrayList<MemberContact>();
                for (String accountId : new LinkedHashSet<>(identities.admins())) {
                    var account = accounts.find(accountId).orElse(null);
                    if (account == null) { continue; }
                    var member = members.byAccount(accountId).orElse(null);
                    if (member != null) { result.add(member); continue; }
                    var emails = account.email() == null ? List.<MemberContact.Email>of()
                            : List.of(new MemberContact.Email(account.email(), account.emailStatus() != null));
                    result.add(new MemberContact(null, accountId, account.email() == null ? "" : account.email(), null, null, account.locale(), emails, List.of(),
                            null, null, List.of()));
                }
                return result;
            }
            @Override public List<MemberContact> instructors() {
                return withAccount(members.findAll(catalogs.instructorRefs().stream().filter(PlanningCatalogAccess.InstructorRef::active)
                        .map(PlanningCatalogAccess.InstructorRef::memberId).filter(Objects::nonNull).distinct().toList()));
            }
            @Override public List<MemberContact> instructorsOf(String classSessionId) {
                List<String> ids;
                try { ids = sessions.require(classSessionId).instructorIds(); }
                catch (com.agilityhub.core.shared.domain.ApiException missing) { return List.of(); }
                return ids == null ? List.of() : instructorsByIds(ids);
            }
            @Override public List<MemberContact> instructorsByIds(Collection<String> instructorIds) {
                if (instructorIds.isEmpty()) { return List.of(); }
                return withAccount(members.findAll(catalogs.instructorMembers(instructorIds).stream().filter(Objects::nonNull).distinct().toList()));
            }
            /** Staff notices go to people who can open the app: an instructor's member record without an account is skipped (as E4–E6). */
            private List<MemberContact> withAccount(List<MemberContact> people) { return people.stream().filter(person -> person.accountId() != null).toList(); }
        };
    }
}
