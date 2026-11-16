"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type FormEvent } from "react";
import { errorMessage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { safeNext } from "@/lib/session";
import { Spinner } from "./ui";

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Shared login / sign-up form with client-side checks that mirror the backend's rules. */
export function AuthForm({ mode }: { mode: "login" | "signup" }) {
  const { login, signup } = useAuth();
  const router = useRouter();
  const next = safeNext(useSearchParams().get("next"));
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const isSignup = mode === "signup";

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (submitting) return;
    if (!EMAIL.test(email.trim())) return setError("Enter a valid email address.");
    if (isSignup && (password.length < 8 || password.length > 72)) return setError("Use a password of 8 to 72 characters.");
    if (!password) return setError("Enter your password.");
    setSubmitting(true);
    setError(null);
    try {
      await (isSignup ? signup : login)(email.trim(), password);
      router.replace(next);
      router.refresh(); // re-run the middleware with the new cookie
    } catch (err) {
      setError(errorMessage(err));
      setSubmitting(false);
    }
  };

  const other = isSignup ? "/login" : "/signup";
  const otherHref = next === "/" ? other : `${other}?next=${encodeURIComponent(next)}`;

  return (
    <div className="mx-auto max-w-sm">
      <h1 className="text-2xl font-semibold tracking-tight">{isSignup ? "Create your account" : "Log in"}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {isSignup ? "Already have one? " : "New here? "}
        <Link href={otherHref} className="font-medium text-indigo-700 hover:underline">
          {isSignup ? "Log in" : "Create an account"}
        </Link>
      </p>
      <form onSubmit={submit} className="card mt-6 space-y-4 p-6" noValidate>
        <label className="block text-sm font-medium text-slate-700">
          Email
          <input className="input mt-1" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="block text-sm font-medium text-slate-700">
          Password
          <input
            className="input mt-1"
            type="password"
            autoComplete={isSignup ? "new-password" : "current-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {isSignup && <span className="mt-1 block text-xs font-normal text-slate-500">At least 8 characters.</span>}
        </label>
        {error && <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-800">{error}</p>}
        <button type="submit" className="btn-primary w-full" disabled={submitting}>
          {submitting ? (<><Spinner /> {isSignup ? "Creating account…" : "Logging in…"}</>) : isSignup ? "Sign up" : "Log in"}
        </button>
      </form>
    </div>
  );
}
