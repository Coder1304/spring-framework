package repro;

import java.util.Properties;

import javax.sql.DataSource;

import org.hibernate.SessionFactory;
import org.springframework.orm.jpa.hibernate.HibernateTransactionManager;
import org.springframework.orm.jpa.hibernate.LocalSessionFactoryBuilder;
import org.springframework.transaction.PlatformTransactionManager;

final class HibernateSupport {

    private HibernateSupport() {
    }

    static SessionFactory sessionFactory(DataSource dataSource, Properties properties) {
        LocalSessionFactoryBuilder builder = new LocalSessionFactoryBuilder(dataSource);
        builder.addAnnotatedClass(Person.class);
        builder.addProperties(properties);
        return builder.buildSessionFactory();
    }

    static PlatformTransactionManager transactionManager(SessionFactory sessionFactory, DataSource dataSource) {
        HibernateTransactionManager transactionManager = new HibernateTransactionManager(sessionFactory);
        transactionManager.setDataSource(dataSource);
        return transactionManager;
    }
}
