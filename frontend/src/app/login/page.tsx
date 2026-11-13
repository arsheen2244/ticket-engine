import { Suspense } from "react";
import { AuthForm } from "@/components/AuthForm";
import { LoadingBlock } from "@/components/ui";

export default function LoginPage() {
  return (
    <Suspense fallback={<LoadingBlock />}>
      <AuthForm mode="login" />
    </Suspense>
  );
}
