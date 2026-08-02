package io.github.monssifechadli99.transactiq.case_management.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

public final class FraudCaseProjectionBootstrapRunner implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(FraudCaseProjectionBootstrapRunner.class);
    private final FraudCaseProjectionBootstrap bootstrap;
    private final ConfigurableApplicationContext context;
    public FraudCaseProjectionBootstrapRunner(FraudCaseProjectionBootstrap bootstrap, ConfigurableApplicationContext context){
        this.bootstrap=bootstrap;this.context=context;
    }
    @Override public void run(ApplicationArguments args){
        var result=bootstrap.run();
        log.info("fraud_case_projection_bootstrap_complete inserted={} skipped={} failed={}",
                result.inserted(),result.skipped(),result.failed());
        SpringApplication.exit(context,()->result.failed()==0?0:1);
    }
}
