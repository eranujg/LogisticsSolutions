package com.aadvixon.tms.platform.tenancy;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wraps the application DataSource so every connection carries the tenant context. */
@Configuration(proxyBeanMethods = false)
class TenancyConfiguration {

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    /** Migrations and bootstrap are done; from now on connections are tenant-scoped. */
    @Bean
    ApplicationListener<ApplicationReadyEvent> armTenantAwareDataSource() {
        return event -> TenantAwareDataSource.ARMED.set(true);
    }
}
