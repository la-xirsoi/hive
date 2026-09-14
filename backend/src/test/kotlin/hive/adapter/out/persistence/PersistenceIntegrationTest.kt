package hive.adapter.out.persistence

import hive.adapter.out.persistence.entity.UserEntity
import hive.adapter.out.persistence.jpa.UserJpaRepository
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.transaction.annotation.Transactional

/**
 * Base class for the persistence integration tests.
 *
 * The context is deliberately **narrow**: [PersistenceTestContext] lists
 * everything in it, and nothing under `hive.application` or `hive.adapter.in` is
 * component-scanned. These tests exercise the outbound adapter against a
 * database and nothing else, so a failure here means "the persistence adapter is
 * wrong" rather than "something, somewhere, failed to start" -- and work in
 * progress elsewhere in the application cannot break them.
 *
 * The database is H2 in `MODE=MSSQLServer` (the `test` profile) because there is
 * no container runtime available -- and **Flyway runs the real
 * `V1__baseline.sql` against it**, with `ddl-auto: validate` then checking that
 * the JPA entity mappings agree with the schema that migration produced. What
 * that does and does not prove is written down in `docs/testing.md`.
 *
 * `@Transactional` here means every test rolls back, so the shared in-memory
 * database stays clean between tests without a truncate script.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [PersistenceTestContext::class],
    // Plain @ContextConfiguration does not read application.yml; this
    // initializer is what applies the `test` profile's datasource, Flyway and
    // ddl-auto settings. @SpringBootTest would do it -- and would also drag in
    // the whole application, which is precisely what this base class avoids.
    initializers = [ConfigDataApplicationContextInitializer::class],
)
@ActiveProfiles("test")
@Transactional
abstract class PersistenceIntegrationTest

/**
 * The whole Spring context these tests run in: a datasource, Hibernate, Flyway,
 * a transaction manager, the five JPA repositories, the five entities, and the
 * five adapters under test. Nothing else.
 *
 * It is a `@TestConfiguration` rather than a `@SpringBootConfiguration` for a
 * specific reason. Test classes are on the classpath when the *application's*
 * own `@SpringBootTest` runs, and `hive.HiveApplication` component-scans the
 * whole `hive` package -- so a plain `@Configuration` here would be swept into
 * the application context and register these repositories a second time.
 * `@TestConfiguration` is excluded from that scan.
 */
@TestConfiguration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = [UserEntity::class])
@EnableJpaRepositories(basePackageClasses = [UserJpaRepository::class])
@ImportAutoConfiguration(
    DataSourceAutoConfiguration::class,
    DataSourceTransactionManagerAutoConfiguration::class,
    TransactionAutoConfiguration::class,
    HibernateJpaAutoConfiguration::class,
    FlywayAutoConfiguration::class,
)
@Import(
    UserPersistenceAdapter::class,
    TeamPersistenceAdapter::class,
    ProjectPersistenceAdapter::class,
    TaskPersistenceAdapter::class,
    CommentPersistenceAdapter::class,
)
class PersistenceTestContext
