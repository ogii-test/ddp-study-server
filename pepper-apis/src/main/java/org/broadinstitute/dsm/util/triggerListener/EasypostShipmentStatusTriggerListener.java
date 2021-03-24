package org.broadinstitute.dsm.util.triggerListener;

import com.netflix.servo.DefaultMonitorRegistry;
import com.netflix.servo.monitor.MonitorConfig;
import com.netflix.servo.monitor.NumberGauge;
import org.broadinstitute.ddp.util.BasicTriggerListener;

import java.util.concurrent.atomic.AtomicInteger;

public class EasypostShipmentStatusTriggerListener extends BasicTriggerListener {

    private static final AtomicInteger jobHealthy = new AtomicInteger(0);
    private static final NumberGauge jobHealthyGauge = new NumberGauge(MonitorConfig.builder("easypost_shipment_status_ok_gauge").build(), jobHealthy);

    //explicitly wire up the metrics using a static initializer
    static {
        DefaultMonitorRegistry.getInstance().register(jobHealthyGauge);
    }

    @Override
    public String getName() {
        return "EASYPOST_SHIPMENT_STATUS";
    }

    protected void monitorJobExecution(boolean veto) {
        if (veto) {
            jobHealthy.set(0); //unable to start
        }
        else {
            jobHealthy.set(1); //able to start
        }
    }
}
