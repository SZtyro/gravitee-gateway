/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.gateway.services.healthcheck;

import io.gravitee.definition.model.Api;
import io.gravitee.definition.model.Endpoint;
import io.gravitee.definition.model.EndpointType;
import io.gravitee.definition.model.endpoint.GrpcEndpoint;
import io.gravitee.definition.model.endpoint.HttpEndpoint;
import io.gravitee.definition.model.services.healthcheck.HealthCheckService;
import io.gravitee.gateway.env.GatewayConfiguration;
import io.gravitee.gateway.services.healthcheck.grpc.GrpcEndpointRule;
import io.gravitee.gateway.services.healthcheck.http.HttpEndpointRule;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
public class EndpointHealthcheckResolver {

    @Autowired
    private GatewayConfiguration gatewayConfiguration;

    /**
     * Returns a {@link Stream} of {@link Endpoint} which have to be health-checked.
     *
     * @param api
     * @return
     */
    public List<EndpointRule> resolve(Api api) {
        HealthCheckService rootHealthCheck = api.getServices().get(HealthCheckService.class);
        boolean hcEnabled = (rootHealthCheck != null && rootHealthCheck.isEnabled());

        // Filter to check only HTTP endpoints
        Stream<HttpEndpoint> httpEndpoints = api.getProxy().getGroups()
                .stream()
                .filter(group -> group.getEndpoints() != null)
                .peek(group -> group.getEndpoints().forEach(endpoint -> {
                    if (HttpEndpoint.class.isAssignableFrom(endpoint.getClass())) {
                        final HttpEndpoint httpEndpoint = ((HttpEndpoint) endpoint);
                        final boolean inherit = endpoint.getInherit() != null && endpoint.getInherit();
                        // inherit or discovered endpoints
                        if (inherit || httpEndpoint.getHttpClientOptions() == null) {
                            httpEndpoint.setHttpClientOptions(group.getHttpClientOptions());
                            httpEndpoint.setHttpClientSslOptions(group.getHttpClientSslOptions());
                            httpEndpoint.setHttpProxy(group.getHttpProxy());
                            httpEndpoint.setHeaders(group.getHeaders());
                        }
                    }
                }))
                .flatMap(group -> group.getEndpoints().stream())
                .filter(endpoint -> HttpEndpoint.class.isAssignableFrom(endpoint.getClass()))
                .map(endpoint -> (HttpEndpoint) endpoint);

        // Filtering endpoints according to tenancy configuration
        if (gatewayConfiguration.tenant().isPresent()) {
            String tenant = gatewayConfiguration.tenant().get();
            httpEndpoints = httpEndpoints
                    .filter(endpoint ->
                            endpoint.getTenants() == null
                            || endpoint.getTenants().isEmpty()
                            || endpoint.getTenants().contains(tenant));
        }

        // Remove backup endpoints
        httpEndpoints = httpEndpoints.filter(endpoint -> !endpoint.isBackup());

        // Keep only endpoints where health-check is enabled or not settled (inherit from service)
        httpEndpoints = httpEndpoints.filter(endpoint -> (
                (endpoint.getHealthCheck() == null && hcEnabled)
                        ||
                        (endpoint.getHealthCheck() != null
                                && endpoint.getHealthCheck().isEnabled()
                                && !endpoint.getHealthCheck().isInherit())
                        ||
                        (endpoint.getHealthCheck() != null
                                && endpoint.getHealthCheck().isEnabled()
                                && endpoint.getHealthCheck().isInherit()
                                && hcEnabled)));

        return httpEndpoints.map((Function<HttpEndpoint, EndpointRule>) endpoint -> {
            HealthCheckService healthcheck = (endpoint.getHealthCheck() == null || endpoint.getHealthCheck().isInherit()) ?
                    rootHealthCheck : endpoint.getHealthCheck();
            if (endpoint.getType() == EndpointType.GRPC) {
                return new GrpcEndpointRule(api.getId(), (GrpcEndpoint) endpoint, healthcheck);
            } else {
                return new HttpEndpointRule(api.getId(), endpoint, healthcheck);
            }
        }).collect(Collectors.toList());
    }

    public <T extends Endpoint> EndpointRule resolve(Api api, T endpoint) {
        if (endpoint.getType() == EndpointType.HTTP || endpoint.getType() == EndpointType.GRPC) {
            HttpEndpoint httpEndpoint = (HttpEndpoint) endpoint;
            HealthCheckService rootHealthCheck = api.getServices().get(HealthCheckService.class);
            boolean hcEnabled = (rootHealthCheck != null && rootHealthCheck.isEnabled());

            if (hcEnabled || httpEndpoint.getHealthCheck() != null) {
                HealthCheckService healthcheck = (httpEndpoint.getHealthCheck() == null || httpEndpoint.getHealthCheck().isInherit()) ?
                        rootHealthCheck : httpEndpoint.getHealthCheck();
                if (endpoint.getType() == EndpointType.HTTP) {
                    return new HttpEndpointRule(api.getId(), httpEndpoint, healthcheck);
                } else if (endpoint.getType() == EndpointType.GRPC) {
                    return new GrpcEndpointRule(api.getId(), (GrpcEndpoint) httpEndpoint, healthcheck);
                }
            }
        }

        return null;
    }
}
