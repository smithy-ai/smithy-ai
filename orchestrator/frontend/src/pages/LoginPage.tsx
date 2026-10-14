import { useState, type FormEvent } from "react";
import {
  Button,
  Center,
  Card,
  TextInput,
  PasswordInput,
  Title,
  Text,
  Stack,
} from "@mantine/core";
import { useSearchParams } from "react-router-dom";
import { csrfHeaders } from "../api/client";

/**
 * Where to go after signing in. Only a page on this site: resolved by the
 * browser's own URL parser against this origin and compared on origin, so
 * the tricks a string check misses (a protocol-relative "//", a backslash,
 * a tab or newline the parser strips) all land on "/".
 */
function safeNext(next: string | null): string {
  if (!next) return "/";
  try {
    const url = new URL(next, window.location.origin);
    if (url.origin !== window.location.origin || url.pathname.startsWith("/login")) return "/";
    return url.pathname + url.search + url.hash;
  } catch {
    return "/";
  }
}

export function LoginPage() {
  const [error, setError] = useState(false);
  const [loading, setLoading] = useState(false);
  const [params] = useSearchParams();

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(false);
    setLoading(true);

    const form = new FormData(e.currentTarget);
    const body = new URLSearchParams();
    body.set("username", form.get("username") as string);
    body.set("password", form.get("password") as string);

    const res = await fetch("/api/login", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded", ...csrfHeaders() },
      body,
    });

    if (res.ok) {
      window.location.href = safeNext(params.get("next"));
    } else {
      setError(true);
      setLoading(false);
    }
  }

  return (
    <Center h="100vh">
      <Card shadow="sm" padding="xl" radius="md" w={400} withBorder>
        <form onSubmit={handleSubmit}>
          <Stack>
            <Title order={3}>Smithy-AI</Title>
            <TextInput
              label="Username"
              name="username"
              defaultValue="admin"
              required
            />
            <PasswordInput label="Password" name="password" required />
            {error && (
              <Text c="red" size="sm">
                Invalid username or password.
              </Text>
            )}
            <Button type="submit" loading={loading} fullWidth>
              Sign in
            </Button>
          </Stack>
        </form>
      </Card>
    </Center>
  );
}
