import { Suspense } from "react";
import { AuthForm } from "@/components/AuthForm";
import { LoadingBlock } from "@/components/ui";

export default function SignupPage() {
  return (
    <Suspense fallback={<LoadingBlock />}>
      <AuthForm mode="signup" />
    </Suspense>
  );
}
