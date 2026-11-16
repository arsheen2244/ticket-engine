"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useState } from "react";
import { useAuth } from "@/lib/auth";

function NavLink({ href, children, onClick }: { href: string; children: string; onClick?: () => void }) {
  const pathname = usePathname();
  const active = href === "/" ? pathname === "/" || pathname.startsWith("/events") : pathname.startsWith(href);
  return (
    <Link
      href={href}
      onClick={onClick}
      aria-current={active ? "page" : undefined}
      className={`rounded-md px-3 py-2 text-sm font-medium ${active ? "bg-indigo-50 text-indigo-700" : "text-slate-700 hover:bg-slate-100"}`}
    >
      {children}
    </Link>
  );
}

export function Navbar() {
  const { state, logout } = useAuth();
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);

  const onLogout = async () => {
    close();
    await logout();
    router.push("/");
    router.refresh();
  };

  const links = (
    <>
      <NavLink href="/" onClick={close}>Events</NavLink>
      {state.status === "authenticated" && <NavLink href="/orders" onClick={close}>My orders</NavLink>}
      {state.user?.role === "ADMIN" && <NavLink href="/admin" onClick={close}>Admin</NavLink>}
    </>
  );

  const account =
    state.status === "loading" ? (
      <span className="h-8 w-24 animate-pulse rounded-md bg-slate-100" aria-hidden />
    ) : state.status === "authenticated" ? (
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-3">
        <span className="truncate text-sm text-slate-600" title={state.user.email}>
          {state.user.email}
        </span>
        <button type="button" onClick={onLogout} className="btn-secondary">
          Log out
        </button>
      </div>
    ) : (
      <div className="flex gap-2">
        <Link href="/login" onClick={close} className="btn-secondary">Log in</Link>
        <Link href="/signup" onClick={close} className="btn-primary">Sign up</Link>
      </div>
    );

  return (
    <header className="sticky top-0 z-20 border-b border-slate-200 bg-white/90 backdrop-blur">
      <nav className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-3" aria-label="Main">
        <Link href="/" className="flex items-center gap-2 font-semibold text-slate-900" onClick={close}>
          <span aria-hidden className="grid h-8 w-8 place-items-center rounded-lg bg-indigo-600 text-white">⚡</span>
          FlashTix
        </Link>
        <div className="hidden flex-1 items-center gap-1 sm:flex">{links}</div>
        <div className="hidden sm:block">{account}</div>
        <button
          type="button"
          className="rounded-md p-2 text-slate-700 hover:bg-slate-100 sm:hidden"
          aria-expanded={open}
          aria-controls="mobile-menu"
          aria-label={open ? "Close menu" : "Open menu"}
          onClick={() => setOpen((o) => !o)}
        >
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
            {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
          </svg>
        </button>
      </nav>
      {open && (
        <div id="mobile-menu" className="flex flex-col gap-3 border-t border-slate-200 px-4 py-3 sm:hidden">
          <div className="flex flex-col gap-1">{links}</div>
          {account}
        </div>
      )}
    </header>
  );
}
