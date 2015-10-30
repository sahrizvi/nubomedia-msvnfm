package org.openbaton.autoscaling.core;

import org.openbaton.autoscaling.catalogue.ScalingStatus;
import org.openbaton.autoscaling.catalogue.VnfrMonitor;
import org.openbaton.catalogue.mano.common.AutoScalePolicy;
import org.openbaton.catalogue.mano.descriptor.VNFComponent;
import org.openbaton.catalogue.mano.descriptor.VNFDConnectionPoint;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.record.Status;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Item;
import org.openbaton.exceptions.NotFoundException;
import org.openbaton.monitoring.interfaces.ResourcePerformanceManagement;
import org.openbaton.plugin.utils.PluginBroker;
import org.openbaton.sdk.NFVORequestor;
import org.openbaton.sdk.api.exception.SDKException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Created by mpa on 27.10.15.
 */

@Service
@Scope("prototype")
class ElasticityTask implements Runnable {

    protected Logger log = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private ListableBeanFactory beanFactory;

    private NFVORequestor nfvoRequestor;

    private VnfrMonitor vnfrMonitor;

//    @Autowired
//    private Environment properties;

    private Properties properties;

    private String nsr_id;

    private String vnfr_id;

    private AutoScalePolicy autoScalePolicy;

    private ResourcePerformanceManagement monitor;

    private String name;

    public void init(VirtualNetworkFunctionRecord vnfr, AutoScalePolicy autoScalePolicy, VnfrMonitor vnfrMonitor, Properties properties) throws NotFoundException {
        this.properties = properties;
        this.nfvoRequestor = new NFVORequestor(this.properties.getProperty("openbaton-username"), this.properties.getProperty("openbaton-password"), this.properties.getProperty("openbaton-url"), this.properties.getProperty("openbaton-port"), "1");
        this.nsr_id = vnfr.getParent_ns_id();
        this.vnfr_id = vnfr.getId();
        this.autoScalePolicy = autoScalePolicy;
        this.name = "ElasticityTask#" + vnfr.getId();
        log.debug("ElasticityTask: Fetching the monitor");
        this.monitor = getMonitor();
        if (monitor==null) {
            throw new NotFoundException("ElasticityTask: Monitor was not found. Cannot start Autoscaling for VNFR with id: " + vnfr_id);
        }
        this.vnfrMonitor = vnfrMonitor;
    }

    @Override
    public void run() {
        log.debug("=======VNFR-MONITOR IN TASK=======");
        log.debug(vnfrMonitor.toString());
        log.debug("ElasticityTask: Checking AutoScalingPolicy " + autoScalePolicy.getAction() + " with id: " + autoScalePolicy.getId() + " VNFR with id: " + vnfr_id);
        if (vnfrMonitor.getState(vnfr_id) == ScalingStatus.READY) {
            VirtualNetworkFunctionRecord vnfr = null;
            try {
                vnfr = nfvoRequestor.getNetworkServiceRecordAgent().getVirtualNetworkFunctionRecord(nsr_id, vnfr_id);
            } catch (SDKException e) {
                log.error(e.getMessage(), e);
            }
            if (vnfr != null) {
                if (vnfr.getStatus() == Status.ACTIVE) {
                    try {
                        List<Item> measurementResults = getRawMeasurementResults(vnfr, autoScalePolicy.getMetric(), Integer.toString(autoScalePolicy.getPeriod()));
                        double finalResult = calculateMeasurementResult(autoScalePolicy, measurementResults);
                        log.debug("ElasticityTask: Final measurement result on vnfr " + vnfr.getId() + " on metric " + autoScalePolicy.getMetric() + " with statistic " + autoScalePolicy.getStatistic() + " is " + finalResult + " " + measurementResults);
                        if (triggerAction(autoScalePolicy, finalResult) && checkFeasibility(vnfr, autoScalePolicy)) {
                            try {
                                if (vnfrMonitor.requestScaling(vnfr_id)) {
                                    log.debug("ElasticityTask: Executing scaling action of AutoScalePolicy with id " + autoScalePolicy.getId());
                                    scale(vnfr, autoScalePolicy);
                                    log.debug("ElasticityTask: Starting cooldown period (" + autoScalePolicy.getCooldown() + "s) for AutoScalePolicy with id: " + autoScalePolicy.getId());
                                    Thread.sleep(autoScalePolicy.getCooldown() * 1000);
                                    log.debug("ElasticityTask: Finished cooldown period (" + autoScalePolicy.getCooldown() + "s) for AutoScalePolicy with id: " + autoScalePolicy.getId());
                                } else {
                                    log.debug("ElasticityTask: Alarm was thrown but another Alarm was faster. So Action is not executed");
                                }
                            } catch (NotFoundException e) {
                                log.error(e.getMessage(), e);
                            } catch (SDKException e) {
                                log.error(e.getMessage(), e);
                            } finally {
                                vnfrMonitor.setState(vnfr_id, ScalingStatus.READY);
                            }
                        } else {
                            log.debug("ElasticityTask: Scaling of AutoScalePolicy with id " + autoScalePolicy.getId() + " is not triggered");
                        }
                        log.debug("ElasticityTask: Starting sleeping period (" + autoScalePolicy.getPeriod() + "s) for AutoScalePolicy with id: " + autoScalePolicy.getId());
                    } catch (InterruptedException e) {
                        log.warn("ElasticityTask: ElasticityTask was interrupted");
                    } catch (RemoteException e) {
                        log.warn("ElasticityTask: Problem with the MonitoringPlugin!");
                    }
                } else {
                    log.debug("ElasticityTask: In state: " + vnfr.getStatus() + " -> waiting until state goes back to " + Status.ACTIVE);
                }
            } else {
                log.error("ElasticityTask: Not found VNFR with id: " + vnfr_id + " of NSR with id: " + nsr_id);
            }
        } else {
            log.debug("ElasticityTask: Cannot check for scaling. Internal state of VNFR is: " + vnfrMonitor.getState(vnfr_id) + ". Waiting for state: " + ScalingStatus.READY);
        }
    }


    public VirtualNetworkFunctionRecord getVnfr(String nsrId, String vnfrId) {
        try {
            return nfvoRequestor.getNetworkServiceRecordAgent().getVirtualNetworkFunctionRecord(nsrId, vnfrId);
        } catch (SDKException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    public ResourcePerformanceManagement getMonitor() {
        PluginBroker<ResourcePerformanceManagement> pluginBroker = new PluginBroker<>();
        ResourcePerformanceManagement monitor = null;
        monitor = new MyMonitor();
        if (true)
            return monitor;
        try {
            monitor = pluginBroker.getPlugin("localhost", "monitor", "smart-dummy", "smart", 19999);
        } catch (RemoteException e) {
            log.error(e.getLocalizedMessage(), e);
        } catch (NotBoundException e) {
            log.warn("Monitoring " + e.getLocalizedMessage() + ". ElasticityManagement will not start.", e);
        }
        return monitor;
    }

    public void scale(VirtualNetworkFunctionRecord vnfr, AutoScalePolicy autoScalePolicy) throws SDKException {
        if (autoScalePolicy.getAction().equals("scaleup")) {
            for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
                if (vdu.getVnfc().size() < vdu.getScale_in_out() && (vdu.getVnfc().iterator().hasNext())) {
                    VNFComponent vnfComponent_copy = vdu.getVnfc().iterator().next();
                    VNFComponent vnfComponent_new = new VNFComponent();
                    vnfComponent_new.setConnection_point(new HashSet<VNFDConnectionPoint>());
                    for (VNFDConnectionPoint vnfdConnectionPoint_copy : vnfComponent_copy.getConnection_point()) {
                        VNFDConnectionPoint vnfdConnectionPoint_new = new VNFDConnectionPoint();
                        vnfdConnectionPoint_new.setVirtual_link_reference(vnfdConnectionPoint_copy.getVirtual_link_reference());
                        vnfdConnectionPoint_new.setType(vnfdConnectionPoint_copy.getType());
                        vnfComponent_new.getConnection_point().add(vnfdConnectionPoint_new);
                    }
                    nfvoRequestor.getNetworkServiceRecordAgent().createVNFCInstance(vnfr.getParent_ns_id(), vnfr.getId(), vdu.getId(), vnfComponent_new);
                    //vdu.getVnfc().add(vnfComponent_new);
                    log.debug("SCALING: Added new Component to VDU " + vdu.getId());
                    return;
                } else {
                    continue;
                }
            }
            log.debug("Not found any VDU to scale out a VNFComponent. Limits are reached.");
        } else if (autoScalePolicy.getAction().equals("scaledown")) {
//            for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
//                if (vdu.getVnfc().size() > 1 && vdu.getVnfc().iterator().hasNext()) {
//                    VNFComponent vnfComponent_remove = vdu.getVnfc().iterator().next();
//                    //vdu.getVnfc().remove(vnfComponent_remove);
            nfvoRequestor.getNetworkServiceRecordAgent().deleteVNFCInstance(vnfr.getParent_ns_id(), vnfr.getId());
//                    log.debug("SCALING: Removed Component " + vnfComponent_remove.getId() + " from VDU " + vdu.getId());
//                    return;
//                } else {
//                    continue;
//                }
//            }
//            log.debug("Not found any VDU to scale in a VNFComponent. Limits are reached.");
        }
    }

    public void scaleVNFCInstances(VirtualNetworkFunctionRecord vnfr) {
        List<Future<VNFCInstance>> vnfcInstances = new ArrayList<>();
        for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
            //Check for additional components for scaling out
            for (VNFComponent vnfComponent : vdu.getVnfc()) {
                //VNFComponent ID is null -> NEW
                boolean found = false;
                //Check if VNFCInstance for VNFComponent already exists
                for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {
                    if (vnfComponent.getId().equals(vnfcInstance.getVnfComponent().getId())) {
                        found = true;
                        break;
                    }
                }
                //If the Instance doesn't exists, allocate a new one
                if (!found) {
//                    try {
//                        Map<String, String> floatgingIps = new HashMap<>();
//                        for (VNFDConnectionPoint connectionPoint : vnfComponent.getConnection_point()){
//                            if (connectionPoint.getFloatingIp() != null && !connectionPoint.getFloatingIp().equals(""))
//                                floatgingIps.put(connectionPoint.getVirtual_link_reference(),connectionPoint.getFloatingIp());
//                        }
//                        String userdata = "#userdata";
//                        //TODO send scale to orchestrator
//                        //Future<VNFCInstance> allocate = resourceManagement.allocate(vdu, vnfr, vnfComponent, userdata, floatgingIps);
//                        //vnfcInstances.add(allocate);
//                        continue;
//                    } catch (VimException e) {
//                        log.error(e.getMessage(), e);
//                        throw new RuntimeException();
//                    } catch (VimDriverException e) {
//                        log.error(e.getMessage(), e);
//                        throw new RuntimeException();
//                    }
                }
            }
            //Check for removed Components to scale in
            Set<VNFCInstance> removed_instances = new HashSet<>();
            for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {
                boolean found = false;
                for (VNFComponent vnfComponent : vdu.getVnfc()) {
                    if (vnfcInstance.getVnfComponent().getId().equals(vnfComponent.getId())) {
                        log.debug("VNCInstance: " + vnfcInstance.toString() + " stays");
                        found = true;
                        //VNFComponent is still existing
                        break;
                    }
                }
                //VNFComponent is not exsting anymore -> Remove VNFCInstance
                if (!found) {
//                    try {
//                        log.debug("VNCInstance: " + vnfcInstance.toString() + " removing");
//                        //TODO send scale to orchestrator
//                        //resourceManagement.release(vnfcInstance, vdu.getVimInstance());
//                        removed_instances.add(vnfcInstance);
//                    } catch (VimException e) {
//                        log.error(e.getMessage(), e);
//                        throw new RuntimeException();
//                    }
                }
            }
            //Remove terminated VNFCInstances
            vdu.getVnfc_instance().removeAll(removed_instances);
        }
        //Print ids of deployed VDUs
        for (Future<VNFCInstance> vnfcInstance : vnfcInstances) {
            try {
                log.debug("Created VNFCInstance with id: " + vnfcInstance.get());
            } catch (InterruptedException e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            } catch (ExecutionException e) {
                log.error(e.getMessage(), e);
                throw new RuntimeException(e.getMessage(), e);
            }
        }
    }

    public synchronized List<Item> getRawMeasurementResults(VirtualNetworkFunctionRecord vnfr, final String metric, String period) throws RemoteException {
        List<Item> measurementResults = new ArrayList<Item>();
        List<String> hostnames = new ArrayList<String>();
        List<String> metrics = new ArrayList<String>();
        metrics.add(metric);
        log.debug("Getting all measurement results for vnfr " + vnfr.getId() + " on metric " + metric + ".");
        for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
            for (final VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {
                hostnames.add(vnfcInstance.getHostname());
            }
        }
        measurementResults.addAll(monitor.getMeasurementResults(hostnames, metrics, period));
        log.debug("Got all measurement results for vnfr " + vnfr.getId() + " on metric " + metric + " -> " + measurementResults + ".");
        return measurementResults;
    }

    public double calculateMeasurementResult(AutoScalePolicy autoScalePolicy, List<Item> measurementResults) {
        double result;
        List<Double> consideredResults = new ArrayList<>();
        for (Item measurementResult : measurementResults) {
            consideredResults.add(Double.parseDouble(measurementResult.getValue()));
        }
        switch (autoScalePolicy.getStatistic()) {
            case "avg":
                double sum = 0;
                for (Double consideredResult : consideredResults) {
                    sum += consideredResult;
                }
                result = sum / measurementResults.size();
                break;
            case "min":
                result = Collections.min(consideredResults);
                break;
            case "max":
                result = Collections.max(consideredResults);
                break;
            default:
                result = -1;
                break;
        }
        return result;
    }

    public boolean triggerAction(AutoScalePolicy autoScalePolicy, double result) {
        switch (autoScalePolicy.getComparisonOperator()) {
            case ">":
                if (result > autoScalePolicy.getThreshold()) {
                    return true;
                }
                break;
            case "<":
                if (result < autoScalePolicy.getThreshold()) {
                    return true;
                }
                break;
            case "=":
                if (result == autoScalePolicy.getThreshold()) {
                    return true;
                }
                break;
            default:
                return false;
        }
        return false;
    }

    public boolean checkFeasibility(VirtualNetworkFunctionRecord vnfr, AutoScalePolicy autoScalePolicy) {
        if (autoScalePolicy.getAction().equals("scaleup")) {
            for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
                if (vdu.getVnfc().size() < vdu.getScale_in_out()) {
                    return true;
                }
            }
            log.debug("Maximum number of instances are reached on all VimInstances");
            return false;
        } else if (autoScalePolicy.getAction().equals("scaledown")) {
            for (VirtualDeploymentUnit vdu : vnfr.getVdu()) {
                if (vdu.getVnfc().size() > 1) {
                    return true;
                }
            }
            log.warn("Cannot terminate the last VDU.");
            return false;
        }
        return true;
    }

    class MyMonitor implements ResourcePerformanceManagement {

        @Override
        public List<Item> getMeasurementResults(List<String> hostnames, List<String> metrics, String period) throws RemoteException {
            List<Item> items = new ArrayList<>();
            for (String hostname : hostnames) {
                for (String metric : metrics) {
                    Item item = new Item();
                    item.setHostId(hostname);
                    item.setHostname(hostname);
                    item.setLastValue(Double.toString(Math.random() * 100));
                    item.setValue(Double.toString(Math.random() * 100));
                    item.setMetric(metric);
                    items.add(item);
                }
            }
            return items;
        }

        @Override
        public void notifyResults() throws RemoteException {

        }
    }



}
