/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.connect.rest.basic.auth.extension;

import org.apache.kafka.common.security.authenticator.TestJaasConfig;
import org.apache.kafka.connect.errors.ConnectException;
import org.easymock.EasyMock;
import org.junit.jupiter.api.Test;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.ChoiceCallback;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.easymock.EasyMock.replay;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JaasBasicAuthFilterTest {

	private static final String LOGIN_MODULE =
			"org.apache.kafka.connect.rest.basic.auth.extension.PropertyFileLoginModule";

	@Test
	public void testSuccess() throws IOException {
		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Basic", "user", "password", false);
		jaasBasicAuthFilter.filter(requestContext);
	}

	@Test
	public void testEmptyCredentialsFile() throws IOException {
		File credentialFile = setupPropertyLoginFile(false);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Basic", "user", "password", false);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

	@Test
	public void testBadCredential() throws IOException {
		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Basic", "user1", "password", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testBadPassword() throws IOException {
		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Basic", "user", "password1", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testUnknownBearer() throws IOException {
		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Unknown", "user", "password", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testUnknownLoginModule() throws IOException {
		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect1", credentialFile.getPath());
		ContainerRequestContext requestContext = setMock("Basic", "user", "password", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testUnknownCredentialsFile() throws IOException {
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", "/tmp/testcrednetial");
		ContainerRequestContext requestContext = setMock("Basic", "user", "password", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testNoFileOption() throws IOException {
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", null);
		ContainerRequestContext requestContext = setMock("Basic", "user", "password", true);
		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testPostWithoutAppropriateCredential() throws IOException {
		UriInfo uriInfo = EasyMock.strictMock(UriInfo.class);
		EasyMock.expect(uriInfo.getPath()).andReturn("connectors/connName/tasks");

		ContainerRequestContext requestContext = EasyMock.strictMock(ContainerRequestContext.class);
		EasyMock.expect(requestContext.getMethod()).andReturn(HttpMethod.POST);
		EasyMock.expect(requestContext.getUriInfo()).andReturn(uriInfo);

		replay(uriInfo, requestContext);

		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect1", credentialFile.getPath());

		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

    @Test
    public void testPostNotChangingConnectorTask() throws IOException {
		UriInfo uriInfo = EasyMock.strictMock(UriInfo.class);
		EasyMock.expect(uriInfo.getPath()).andReturn("local:randomport/connectors/connName");

		ContainerRequestContext requestContext = EasyMock.strictMock(ContainerRequestContext.class);
		EasyMock.expect(requestContext.getMethod()).andReturn(HttpMethod.POST);
		EasyMock.expect(requestContext.getUriInfo()).andReturn(uriInfo);
		String authHeader = "Basic" + Base64.getEncoder().encodeToString(("user" + ":" + "password").getBytes());
		EasyMock.expect(requestContext.getHeaderString(JaasBasicAuthFilter.AUTHORIZATION))
				.andReturn(authHeader);
		requestContext.abortWith(EasyMock.anyObject(Response.class));
		EasyMock.expectLastCall();

		replay(uriInfo, requestContext);

		File credentialFile = setupPropertyLoginFile(true);
		JaasBasicAuthFilter jaasBasicAuthFilter = setupJaasFilter("KafkaConnect", credentialFile.getPath());

		jaasBasicAuthFilter.filter(requestContext);
		EasyMock.verify(requestContext);
	}

	@Test
	public void testUnsupportedCallback() {
		String authHeader = authHeader("basic", "user", "pwd");
		CallbackHandler callbackHandler = new JaasBasicAuthFilter.BasicAuthCallBackHandler(authHeader);
		Callback unsupportedCallback = new ChoiceCallback(
				"You take the blue pill... the story ends, you wake up in your bed and believe whatever you want to believe. "
						+ "You take the red pill... you stay in Wonderland, and I show you how deep the rabbit hole goes.",
				new String[]{"blue pill", "red pill"},
				1,
				true
		);
		assertThrows(ConnectException.class, () -> callbackHandler.handle(new Callback[]{unsupportedCallback}));
	}

	private String authHeader(String authorization, String username, String password) {
		return authorization + " " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
	}

	private ContainerRequestContext setMock(String authorization, String username, String password, boolean exceptionCase) {
		ContainerRequestContext requestContext = EasyMock.strictMock(ContainerRequestContext.class);
		EasyMock.expect(requestContext.getMethod()).andReturn(HttpMethod.GET);
		EasyMock.expect(requestContext.getHeaderString(JaasBasicAuthFilter.AUTHORIZATION))
				.andReturn(authHeader(authorization, username, password));
		if (exceptionCase) {
			requestContext.abortWith(EasyMock.anyObject(Response.class));
			EasyMock.expectLastCall();
		}
		replay(requestContext);
		return requestContext;
	}

	private File setupPropertyLoginFile(boolean includeUsers) throws IOException {
		File credentialFile = File.createTempFile("credential", ".properties");
		credentialFile.deleteOnExit();
		if (includeUsers) {
			List<String> lines = new ArrayList<>();
			lines.add("user=password");
			lines.add("user1=password1");
			Files.write(credentialFile.toPath(), lines, StandardCharsets.UTF_8);
		}
		return credentialFile;
	}

	private JaasBasicAuthFilter setupJaasFilter(String name, String credentialFilePath) {
		TestJaasConfig configuration = new TestJaasConfig();
		Map<String, Object> moduleOptions = credentialFilePath != null
				? Collections.singletonMap("file", credentialFilePath)
				: Collections.emptyMap();
		configuration.addEntry(name, LOGIN_MODULE, moduleOptions);
		return new JaasBasicAuthFilter(configuration);
	}

}
