/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.server.manager.management;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import org.jboss.as.deployment.client.api.domain.DeploymentPlan;
import org.jboss.as.deployment.client.api.domain.DeploymentPlanResult;
import org.jboss.as.deployment.client.api.domain.DomainDeploymentManager;
import org.jboss.as.domain.client.api.DomainUpdateResult;
import org.jboss.as.domain.client.api.ServerIdentity;
import org.jboss.as.domain.client.impl.DomainUpdateApplierResponse;
import org.jboss.as.domain.client.impl.DomainClientProtocol;
import org.jboss.as.domain.client.impl.UpdateResultHandlerResponse;
import org.jboss.as.domain.controller.DomainController;
import org.jboss.as.domain.controller.deployment.DomainDeploymentRepository;
import org.jboss.as.model.AbstractDomainModelUpdate;
import org.jboss.as.model.AbstractServerModelUpdate;
import org.jboss.as.model.UpdateFailedException;
import org.jboss.as.protocol.ByteDataInput;
import org.jboss.as.protocol.ByteDataOutput;
import org.jboss.as.protocol.ChunkyByteInput;
import org.jboss.as.protocol.Connection;
import org.jboss.as.protocol.SimpleByteDataInput;
import org.jboss.as.protocol.SimpleByteDataOutput;
import org.jboss.as.protocol.StreamUtils;

import static org.jboss.as.server.manager.management.ManagementUtils.expectHeader;
import static org.jboss.as.server.manager.management.ManagementUtils.getMarshaller;
import static org.jboss.as.server.manager.management.ManagementUtils.getUnmarshaller;
import org.jboss.logging.Logger;
import org.jboss.marshalling.Marshaller;
import static org.jboss.marshalling.Marshalling.createByteInput;
import static org.jboss.marshalling.Marshalling.createByteOutput;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;


/**
 * {@link org.jboss.as.server.manager.management.ManagementOperationHandler} implementation used to handle request
 * intended for the domain controller.
 *
 * @author John Bailey
 */
public class  DomainControllerClientOperationHandler extends AbstractMessageHandler implements ManagementOperationHandler, Service<DomainControllerClientOperationHandler> {
    private static final Logger log = Logger.getLogger("org.jboss.as.management");
    public static final ServiceName SERVICE_NAME = DomainController.SERVICE_NAME.append("client", "operation", "handler");

    private final InjectedValue<DomainController> domainControllerValue = new InjectedValue<DomainController>();
    private final InjectedValue<DomainDeploymentManager> domainDeploymentManagerValue = new InjectedValue<DomainDeploymentManager>();
    private final InjectedValue<DomainDeploymentRepository> domainDeploymentRepositoryValue = new InjectedValue<DomainDeploymentRepository>();

    private DomainController domainController;
    private DomainDeploymentManager deploymentManager;
    private DomainDeploymentRepository deploymentRepository;

    /** {@inheritDoc} */
    public final byte getIdentifier() {
        return ManagementProtocol.DOMAIN_CONTROLLER_CLIENT_REQUEST;
    }

    /** {@inheritDoc} */
    public synchronized void start(StartContext context) throws StartException {
        try {
            domainController = domainControllerValue.getValue();
            deploymentManager = domainDeploymentManagerValue.getValue();
            deploymentRepository = domainDeploymentRepositoryValue.getValue();
        } catch (IllegalStateException e) {
            throw new StartException(e);
        }
    }

    /** {@inheritDoc} */
    public synchronized void stop(StopContext context) {
        domainController = null;
    }

    /** {@inheritDoc} */
    public synchronized DomainControllerClientOperationHandler getValue() throws IllegalStateException {
        return this;
    }

    public Injector<DomainController> getDomainControllerInjector() {
        return domainControllerValue;
    }

    public Injector<DomainDeploymentManager> getDomainDeploymentManagerInjector() {
        return domainDeploymentManagerValue;
    }

    public Injector<DomainDeploymentRepository> getDomainDeploymentRepositoryInjector() {
        return domainDeploymentRepositoryValue;
    }

    /**
     * Handles the request.  Reads the requested command byte. Once the command is available it will get the
     * appropriate operation and execute it.
     *
     * @param connection  The connection
     * @param input The connection input
     * @throws ManagementException If any problems occur performing the operation
     */
    @Override
    public void handle(final Connection connection, final InputStream input) throws ManagementException {
        final byte commandCode;
        try {
            expectHeader(input, DomainClientProtocol.REQUEST_OPERATION);
            commandCode = StreamUtils.readByte(input);

            final ManagementOperation operation = operationFor(commandCode);
            if (operation == null) {
                throw new ManagementException("Invalid command code " + commandCode + " received from server manager");
            }
            log.debugf("Received DomainClient operation [%s]", operation);

            try {
                operation.handle(connection, input);
            } catch (Exception e) {
                throw new ManagementException("Failed to execute domain client operation", e);
            }
        } catch (ManagementException e) {
            throw e;
        } catch (Throwable t) {
            throw new ManagementException("DomainController Request failed to read command code", t);
        }
    }

    private ManagementOperation operationFor(final byte commandByte) {
        switch (commandByte) {
            case DomainClientProtocol.GET_DOMAIN_REQUEST:
                return new GetDomainOperation();
            case DomainClientProtocol.APPLY_UPDATES_REQUEST:
                return new ApplyDomainModelUpdatesOperation();
            case DomainClientProtocol.APPLY_UPDATE_REQUEST:
                return new ApplyDomainModelUpdateOperation();
            case DomainClientProtocol.EXECUTE_DEPLOYMENT_PLAN_REQUEST:
                return new ExecuteDeploymentPlanOperation();
            case DomainClientProtocol.ADD_DEPLOYMENT_CONTENT_REQUEST:
                return new AddDeploymentContentOperation();
            case DomainClientProtocol.APPLY_SERVER_MODEL_UPDATE_REQUEST:
                return new ApplyServerModelUpdateOperation();
            default: {
                return null;
            }
        }
    }

    private class GetDomainOperation extends ManagementResponse {

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.GET_DOMAIN_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.GET_DOMAIN_RESPONSE;
        }

        @Override
        protected void sendResponse(final OutputStream outputStream) throws ManagementException {
            try {
                final Marshaller marshaller = getMarshaller();
                marshaller.start(createByteOutput(outputStream));
                marshaller.writeByte(DomainClientProtocol.PARAM_DOMAIN_MODEL);
                marshaller.writeObject(domainController.getDomainModel());
                marshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to write domain configuration to client", e);
            }
        }
    }

    private class ApplyDomainModelUpdatesOperation extends ManagementResponse {
        private List<AbstractDomainModelUpdate<?>> updates;

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.APPLY_UPDATES_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.APPLY_UPDATES_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream inputStream) throws ManagementException {
            try {
                final Unmarshaller unmarshaller = getUnmarshaller();
                unmarshaller.start(createByteInput(inputStream));
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_APPLY_UPDATES_RESULT_COUNT);
                int count = unmarshaller.readInt();
                updates = new ArrayList<AbstractDomainModelUpdate<?>>(count);
                for (int i = 0; i < count; i++) {
                    expectHeader(unmarshaller, DomainClientProtocol.PARAM_DOMAIN_MODEL_UPDATE);
                    final AbstractDomainModelUpdate<?> update = unmarshaller.readObject(AbstractDomainModelUpdate.class);
                    updates.add(update);
                }
                unmarshaller.finish();
                log.infof("Received domain model updates %s", updates);
            } catch (Exception e) {
                throw new ManagementException("Unable to read domain model updates from request", e);
            }
        }

        @Override
        protected void sendResponse(final OutputStream output) throws ManagementException {
            List<DomainUpdateResult<?>> responses = new ArrayList<DomainUpdateResult<?>>(updates.size());
            for (AbstractDomainModelUpdate<?> update : updates) {
                responses.add(processUpdate(update));
            }
            try {
                final Marshaller marshaller = getMarshaller();
                marshaller.start(createByteOutput(output));
                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATES_RESULT_COUNT);
                marshaller.writeInt(responses.size());
                for (DomainUpdateResult<?> response : responses) {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT);
                    if (response.getDomainFailure() != null) {
                        marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                        marshaller.writeObject(response.getDomainFailure());
                    } else {
                        marshaller.writeByte(DomainClientProtocol.APPLY_UPDATE_RESULT_DOMAIN_MODEL_SUCCESS);
                        marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_HOST_FAILURE_COUNT);
                        Map<String, UpdateFailedException> hostFailures = response.getHostFailures();
                        if (hostFailures == null || hostFailures.size() == 0) {
                            marshaller.writeInt(0);
                        } else {
                            marshaller.writeInt(hostFailures.size());
                            for (Map.Entry<String, UpdateFailedException> entry : hostFailures.entrySet()) {
                                marshaller.writeByte(DomainClientProtocol.PARAM_HOST_NAME);
                                marshaller.writeUTF(entry.getKey());
                                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                                marshaller.writeObject(entry.getValue());
                            }
                        }
                        marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_SERVER_FAILURE_COUNT);
                        Map<ServerIdentity, Throwable> serverFailures = response.getServerFailures();
                        if (serverFailures == null || serverFailures.size() == 0) {
                            marshaller.writeInt(0);
                        } else {
                            marshaller.writeInt(serverFailures.size());
                            for (Map.Entry<ServerIdentity, Throwable> entry : serverFailures.entrySet()) {
                                ServerIdentity identity = entry.getKey();
                                marshaller.writeByte(DomainClientProtocol.PARAM_HOST_NAME);
                                marshaller.writeUTF(identity.getHostName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_GROUP_NAME);
                                marshaller.writeUTF(identity.getServerGroupName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_NAME);
                                marshaller.writeUTF(identity.getServerName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                                marshaller.writeObject(entry.getValue());
                            }
                        }
                        marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_SERVER_RESULT_COUNT);
                        Map<ServerIdentity, ?> serverResults = response.getServerResults();
                        if (serverResults == null || serverResults.size() == 0) {
                            marshaller.writeInt(0);
                        } else {
                            marshaller.writeInt(serverResults.size());
                            for (Map.Entry<ServerIdentity, ?> entry : serverFailures.entrySet()) {
                                ServerIdentity identity = entry.getKey();
                                marshaller.writeByte(DomainClientProtocol.PARAM_HOST_NAME);
                                marshaller.writeUTF(identity.getHostName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_GROUP_NAME);
                                marshaller.writeUTF(identity.getServerGroupName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_NAME);
                                marshaller.writeUTF(identity.getServerName());
                                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_SERVER_MODEL_UPDATE_RESULT_RETURN);
                                marshaller.writeObject(entry.getValue());
                            }
                        }
                    }
                }
                marshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to send domain model update response.", e);
            }
        }

        private DomainUpdateResult<?> processUpdate(final AbstractDomainModelUpdate<?> update) {
            return domainController.applyUpdate(update);
        }
    }

    private class ApplyDomainModelUpdateOperation extends ManagementResponse {
        private AbstractDomainModelUpdate<?> update;

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.APPLY_UPDATE_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.APPLY_UPDATE_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream input) throws ManagementException {
            try {
                final Unmarshaller unmarshaller = getUnmarshaller();
                unmarshaller.start(createByteInput(input));
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_DOMAIN_MODEL_UPDATE);
                update = unmarshaller.readObject(AbstractDomainModelUpdate.class);
                unmarshaller.finish();
                log.infof("Received domain model update %s", update);
            } catch (Exception e) {
                throw new ManagementException("Unable to read domain model updates from request", e);
            }
        }

        @Override
        protected void sendResponse(final OutputStream output) throws ManagementException {
            DomainUpdateApplierResponse response = processUpdate();
            try {
                final Marshaller marshaller = getMarshaller();
                marshaller.start(createByteOutput(output));
                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT);
                if (response.getDomainFailure() != null) {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                    marshaller.writeObject(response.getDomainFailure());
                } else {
                    marshaller.writeByte(DomainClientProtocol.APPLY_UPDATE_RESULT_DOMAIN_MODEL_SUCCESS);
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_HOST_FAILURE_COUNT);
                    Map<String, UpdateFailedException> hostFailures = response.getHostFailures();
                    if (hostFailures == null || hostFailures.size() == 0) {
                        marshaller.writeInt(0);
                    } else {
                        marshaller.writeInt(hostFailures.size());
                        for (Map.Entry<String, UpdateFailedException> entry : hostFailures.entrySet()) {
                            marshaller.writeByte(DomainClientProtocol.PARAM_HOST_NAME);
                            marshaller.writeUTF(entry.getKey());
                            marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                            marshaller.writeObject(entry.getValue());
                        }
                    }
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_SERVER_COUNT);
                    List<ServerIdentity> servers = response.getServers();
                    if (servers == null || servers.size() == 0) {
                        marshaller.writeInt(0);
                    } else {
                        marshaller.writeInt(servers.size());
                        for (ServerIdentity server : servers) {
                            marshaller.writeByte(DomainClientProtocol.PARAM_HOST_NAME);
                            marshaller.writeUTF(server.getHostName());
                            marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_GROUP_NAME);
                            marshaller.writeUTF(server.getServerGroupName());
                            marshaller.writeByte(DomainClientProtocol.PARAM_SERVER_NAME);
                            marshaller.writeUTF(server.getServerName());
                        }
                    }
                }
                marshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to send domain model update response.", e);
            }
        }

        private DomainUpdateApplierResponse processUpdate() {
            return domainController.applyUpdateToModel(update);
        }
    }

    private class ApplyServerModelUpdateOperation extends ManagementResponse {
        private AbstractServerModelUpdate<?> update;
        private ServerIdentity server;

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.APPLY_SERVER_MODEL_UPDATE_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.APPLY_SERVER_MODEL_UPDATE_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream input) throws ManagementException {
            try {
                final Unmarshaller unmarshaller = getUnmarshaller();
                unmarshaller.start(createByteInput(input));
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_HOST_NAME);
                String hostName = unmarshaller.readUTF();
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_SERVER_GROUP_NAME);
                String serverGroupName = unmarshaller.readUTF();
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_SERVER_NAME);
                String serverName = unmarshaller.readUTF();
                server = new ServerIdentity(hostName, serverGroupName, serverName);
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_SERVER_MODEL_UPDATE);
                update = unmarshaller.readObject(AbstractServerModelUpdate.class);
                unmarshaller.finish();
                log.infof("Received server model update %s", update);
            } catch (Exception e) {
                throw new ManagementException("Unable to read domain model updates from request", e);
            }
        }

        @Override
        protected void sendResponse(final OutputStream output) throws ManagementException {
            UpdateResultHandlerResponse<?> response = processUpdate();
            try {
                final Marshaller marshaller = getMarshaller();
                marshaller.start(createByteOutput(output));
                marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT);
                if (response.getFailureResult() != null) {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_UPDATE_RESULT_EXCEPTION);
                    marshaller.writeObject(response.getFailureResult());
                } else if (response.isCancelled()) {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_SERVER_MODEL_UPDATE_CANCELLED);
                } else if (response.isTimedOut()) {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_SERVER_MODEL_UPDATE_TIMED_OUT);
                } else {
                    marshaller.writeByte(DomainClientProtocol.PARAM_APPLY_SERVER_MODEL_UPDATE_RESULT_RETURN);
                    marshaller.writeObject(response.getSuccessResult());
                }
                marshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to send domain model update response.", e);
            }
        }

        private UpdateResultHandlerResponse<?> processUpdate() {
            List<UpdateResultHandlerResponse<?>> list =
                    domainController.applyUpdateToServer(Collections.<AbstractServerModelUpdate<?>>singletonList(update), server);
            return list.get(0);
        }
    }

    private class ExecuteDeploymentPlanOperation extends ManagementResponse {
        private DeploymentPlan deploymentPlan;

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.EXECUTE_DEPLOYMENT_PLAN_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.EXECUTE_DEPLOYMENT_PLAN_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream input) throws ManagementException {
            try {
                final Unmarshaller unmarshaller = getUnmarshaller();
                unmarshaller.start(createByteInput(input));
                expectHeader(unmarshaller, DomainClientProtocol.PARAM_DEPLOYMENT_PLAN);
                deploymentPlan = unmarshaller.readObject(DeploymentPlan.class);
                unmarshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to read deployment plan from request", e);
            }
        }

        @Override
        protected void sendResponse(final OutputStream output) throws ManagementException {
            try {
                final Future<DeploymentPlanResult> result = deploymentManager.execute(deploymentPlan);
                final Marshaller marshaller = getMarshaller();
                marshaller.start(createByteOutput(output));
                marshaller.writeByte(DomainClientProtocol.PARAM_DEPLOYMENT_PLAN_RESULT);
                marshaller.writeObject(result.get());
                marshaller.finish();
            } catch (Exception e) {
                throw new ManagementException("Unable to send deployment plan result.", e);
            }
        }
    }

    private class AddDeploymentContentOperation extends ManagementResponse {
        private byte[] deploymentHash;

        @Override
        public final byte getRequestCode() {
            return DomainClientProtocol.ADD_DEPLOYMENT_CONTENT_REQUEST;
        }

        @Override
        protected final byte getResponseCode() {
            return DomainClientProtocol.ADD_DEPLOYMENT_CONTENT_RESPONSE;
        }

        @Override
        protected final void readRequest(final InputStream inputStream) throws ManagementException {
            ByteDataInput input = null;
            try {
                input = new SimpleByteDataInput(inputStream);
                expectHeader(input, DomainClientProtocol.PARAM_DEPLOYMENT_NAME);
                final String deploymentName = input.readUTF();
                expectHeader(input, DomainClientProtocol.PARAM_DEPLOYMENT_RUNTIME_NAME);
                final String deploymentRuntimeName = input.readUTF();
                expectHeader(input, DomainClientProtocol.PARAM_DEPLOYMENT_CONTENT);
                final ChunkyByteInput contentInput = new ChunkyByteInput(input);
                try {
                    deploymentHash = deploymentRepository.addDeploymentContent(deploymentName, deploymentRuntimeName, contentInput);
                } finally {
                    contentInput.close();
                }
            } catch (Exception e) {
                throw new ManagementException("Unable to read deployment content from request", e);
            }
        }

        @Override
        protected void sendResponse(final OutputStream outputStream) throws ManagementException {
            ByteDataOutput output = null;
            try {
                output = new SimpleByteDataOutput(outputStream);
                output.writeByte(DomainClientProtocol.PARAM_DEPLOYMENT_HASH_LENGTH);
                output.writeInt(deploymentHash.length);
                output.writeByte(DomainClientProtocol.PARAM_DEPLOYMENT_HASH);
                output.write(deploymentHash);
            } catch (Exception e) {
                throw new ManagementException("Unable to send deployment hash", e);
            }
        }
    }
}
