package com.example.ticketengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test helper: creates accounts through the real signup/login endpoints and returns their tokens. */
public final class TestAuth {

    public static final String PASSWORD = "correct-horse-1";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Account(long id, String email, String token) {
        public String bearer() { return "Bearer " + token; }
    }

    private TestAuth() {}

    public static String uniqueEmail() { return "user-" + UUID.randomUUID() + "@example.com"; }

    public static String credentials(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    public static Account signup(MockMvc mvc) throws Exception {
        return signup(mvc, uniqueEmail());
    }

    public static Account signup(MockMvc mvc, String email) throws Exception {
        String body = mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, PASSWORD)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return parse(body);
    }

    public static Account login(MockMvc mvc, String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return parse(body);
    }

    private static Account parse(String body) throws Exception {
        JsonNode n = JSON.readTree(body);
        return new Account(n.at("/user/id").asLong(), n.at("/user/email").asText(), n.get("token").asText());
    }
}
