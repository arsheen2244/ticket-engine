"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, ApiError } from "./api";
import type { User } from "./types";

type AuthState =
  | { status: "loading"; user: null }
  | { status: "anonymous"; user: null }
  | { status: "authenticated"; user: User };

interface AuthContextValue {
  state: AuthState;
  login: (email: string, password: string) => Promise<User>;
  signup: (email: string, password: string) => Promise<User>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

interface AuthResponse {
  user: User;
}

/** Knows who is signed in. The token itself lives in an httpOnly cookie that scripts cannot read. */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "loading", user: null });

  useEffect(() => {
    let cancelled = false;
    api<User>("/api/auth/me")
      .then((user) => !cancelled && setState({ status: "authenticated", user }))
      .catch((e) => {
        // 401 = not logged in. Any other failure: treat as logged out; protected pages show their own error.
        if (!cancelled) setState({ status: "anonymous", user: null });
        if (!(e instanceof ApiError) || e.status !== 401) console.warn("Could not load the session", e);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const signIn = useCallback(async (path: string, email: string, password: string) => {
    const res = await api<AuthResponse>(path, { method: "POST", json: { email, password } });
    setState({ status: "authenticated", user: res.user });
    return res.user;
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      login: (email, password) => signIn("/api/auth/login", email, password),
      signup: (email, password) => signIn("/api/auth/signup", email, password),
      logout: async () => {
        try {
          await api<void>("/api/auth/logout", { method: "POST" });
        } finally {
          setState({ status: "anonymous", user: null });
        }
      },
    }),
    [state, signIn],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside <AuthProvider>");
  return ctx;
}
