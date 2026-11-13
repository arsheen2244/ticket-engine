import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { server } from "@/test/server";
import { AuthProvider } from "@/lib/auth";
import { AuthForm } from "./AuthForm";

const replace = vi.fn();
let search = new URLSearchParams();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace, refresh: vi.fn() }),
  useSearchParams: () => search,
}));

function renderForm(mode: "login" | "signup") {
  server.use(http.get("*/api/auth/me", () => HttpResponse.json({ error: "Please log in" }, { status: 401 })));
  return render(
    <AuthProvider>
      <AuthForm mode={mode} />
    </AuthProvider>,
  );
}

describe("AuthForm", () => {
  beforeEach(() => {
    replace.mockReset();
    search = new URLSearchParams();
  });

  it("logs in and returns to the page the user came from", async () => {
    search = new URLSearchParams("next=/events/4");
    let body: unknown;
    server.use(
      http.post("*/api/auth/login", async ({ request }) => {
        body = await request.json();
        return HttpResponse.json({ user: { id: 1, email: "a@b.co", role: "USER" }, token: "t", expiresAt: "x" });
      }),
    );
    renderForm("login");
    await userEvent.type(screen.getByLabelText("Email"), "a@b.co");
    await userEvent.type(screen.getByLabelText("Password"), "secret-pass");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));

    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/events/4"));
    expect(body).toEqual({ email: "a@b.co", password: "secret-pass" });
  });

  it("shows the backend's message when login fails", async () => {
    server.use(http.post("*/api/auth/login", () => HttpResponse.json({ error: "Invalid email or password" }, { status: 401 })));
    renderForm("login");
    await userEvent.type(screen.getByLabelText("Email"), "a@b.co");
    await userEvent.type(screen.getByLabelText("Password"), "wrong-pass");
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(replace).not.toHaveBeenCalled();
  });

  it("checks the password length before signing up", async () => {
    renderForm("signup");
    await userEvent.type(screen.getByLabelText("Email"), "new@b.co");
    await userEvent.type(screen.getByLabelText(/Password/), "short");
    await userEvent.click(screen.getByRole("button", { name: "Sign up" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("8 to 72 characters");
  });

  it("never redirects off-site after login", async () => {
    search = new URLSearchParams("next=https://evil.example");
    server.use(
      http.post("*/api/auth/signup", () =>
        HttpResponse.json({ user: { id: 2, email: "n@b.co", role: "USER" }, token: "t", expiresAt: "x" }, { status: 201 }),
      ),
    );
    renderForm("signup");
    await userEvent.type(screen.getByLabelText("Email"), "n@b.co");
    await userEvent.type(screen.getByLabelText(/Password/), "long-enough-1");
    await userEvent.click(screen.getByRole("button", { name: "Sign up" }));
    await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/"));
  });
});
