import Link from "next/link";

export default function NotFound() {
  return (
    <div className="card mx-auto max-w-md p-8 text-center">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <p className="mt-1 text-slate-600">That page doesn&apos;t exist.</p>
      <Link href="/" className="btn-primary mt-4">See all sales</Link>
    </div>
  );
}
