package repro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.Properties;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Typical batch pattern: a non-transactional outer scope (PROPAGATION_NEVER / PROPAGATION_SUPPORTS without an existing
 * transaction, default SYNCHRONIZATION_ALWAYS) reads via {@code getCurrentSession()} and then writes in a nested
 * PROPAGATION_REQUIRED transaction. Passes on Spring 6.2, fails on 7.0.x (incl. the gh-37693 fix).
 */
class NonTransactionalScopeConnectionTest {

    private BoundedDataSource dataSource;
    private SessionFactory sessionFactory;
    private PlatformTransactionManager transactionManager;

    private void setUp(int poolSize) {
        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource = new BoundedDataSource(h2, poolSize, 500);

        Properties properties = new Properties();
        properties.put("hibernate.hbm2ddl.auto", "create-drop");
        sessionFactory = HibernateSupport.sessionFactory(dataSource, properties);
        transactionManager = HibernateSupport.transactionManager(sessionFactory, dataSource);
        dataSource.resetStatistics();
    }

    @AfterEach
    void tearDown() {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "PROPAGATION_NEVER", "PROPAGATION_SUPPORTS" })
    void usesAtMostOneConnectionAtATime(String outerPropagation) {
        setUp(10);

        runScenario(outerPropagation);

        assertThat(dataSource.maxBorrowed())
                .as("max. concurrently borrowed connections")
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = { "PROPAGATION_NEVER", "PROPAGATION_SUPPORTS" })
    void worksWithSingleConnectionPool(String outerPropagation) {
        setUp(1);

        assertThatNoException().isThrownBy(() -> runScenario(outerPropagation));
    }

    private void runScenario(String outerPropagation) {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        outer.setPropagationBehaviorName(outerPropagation);
        outer.setReadOnly(true);

        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        outer.executeWithoutResult(outerStatus -> {
            sessionFactory.getCurrentSession()
                    .createQuery("from Person", Person.class)
                    .getResultList();

            // a slow remote call would happen here

            nested.executeWithoutResult(nestedStatus ->
                    sessionFactory.getCurrentSession().persist(new Person("John")));
        });

        assertThat(countPersons()).as("row inserted by nested transaction").isEqualTo(1);
    }

    private long countPersons() {
        try (Session session = sessionFactory.openSession()) {
            return session.createQuery("select count(p) from Person p", Long.class).getSingleResult();
        }
    }
}
