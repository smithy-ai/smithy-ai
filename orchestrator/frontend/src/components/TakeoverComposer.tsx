import { useEffect, useRef, useState } from "react";
import { Box, Button, CloseButton, Group, Image, Stack, Text, Textarea } from "@mantine/core";
import { sendTakeoverMessage } from "../api/client";

/**
 * Matches the server's own ceiling. Checked here too so a mistake is a sentence
 * in the composer rather than a round trip that ends in a 400.
 */
const MAX_SCREENSHOTS = 5;
const ACCEPTED = ["image/png", "image/jpeg", "image/gif", "image/webp"];

interface Attachment {
  file: File;
  /** An object URL for the thumbnail, revoked when the attachment goes. */
  preview: string;
}

interface TakeoverComposerProps {
  containerName: string;
  onError: (message: string | null) => void;
  /** Called once a turn settles, so the transcript above can be refetched. */
  onSent: () => void;
}

/**
 * The message box a person types into while they hold a session.
 *
 * <p>Screenshots matter here more than anywhere else in the dashboard: someone
 * takes over precisely when something looks wrong, and a picture of it says
 * what a paragraph struggles to. Paste is the path that gets used — a screen
 * grab is already on the clipboard — with a picker and drag-and-drop behind it.
 */
export function TakeoverComposer({ containerName, onError, onSent }: TakeoverComposerProps) {
  const [draft, setDraft] = useState("");
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [sending, setSending] = useState(false);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  // Previews are object URLs; letting them outlive the component leaks the
  // image behind each one for as long as the tab is open. Mirrored into a ref
  // because the unmount cleanup would otherwise close over the first render's
  // empty list and revoke nothing.
  const liveAttachments = useRef<Attachment[]>([]);
  useEffect(() => {
    liveAttachments.current = attachments;
  }, [attachments]);
  useEffect(
    () => () => {
      liveAttachments.current.forEach((a) => URL.revokeObjectURL(a.preview));
    },
    [],
  );

  function addFiles(files: File[]) {
    const images = files.filter((file) => ACCEPTED.includes(file.type));
    if (images.length === 0) {
      if (files.length > 0) onError("Only PNG, JPEG, GIF and WebP images can be attached.");
      return;
    }
    setAttachments((current) => {
      const room = MAX_SCREENSHOTS - current.length;
      if (room <= 0) {
        onError(`At most ${MAX_SCREENSHOTS} screenshots per message.`);
        return current;
      }
      if (images.length > room) {
        onError(`Only the first ${room} of those fit — ${MAX_SCREENSHOTS} screenshots per message.`);
      } else {
        onError(null);
      }
      const added = images.slice(0, room).map((file) => ({
        file,
        preview: URL.createObjectURL(file),
      }));
      return [...current, ...added];
    });
  }

  function removeAt(index: number) {
    setAttachments((current) => {
      const going = current[index];
      if (going) URL.revokeObjectURL(going.preview);
      return current.filter((_, i) => i !== index);
    });
  }

  async function handleSend() {
    if (sending) return;
    const text = draft.trim();
    if (!text && attachments.length === 0) return;
    setSending(true);
    onError(null);
    try {
      await sendTakeoverMessage(
        containerName,
        text,
        attachments.map((a) => a.file),
      );
      setDraft("");
      attachments.forEach((a) => URL.revokeObjectURL(a.preview));
      setAttachments([]);
    } catch (e) {
      onError(e instanceof Error ? e.message : "Failed to send message.");
    } finally {
      setSending(false);
      onSent();
    }
  }

  const empty = !draft.trim() && attachments.length === 0;

  return (
    <Stack
      gap="xs"
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        addFiles(Array.from(e.dataTransfer.files));
      }}
      style={{
        outline: dragging ? "2px dashed var(--mantine-color-blue-5)" : undefined,
        outlineOffset: 4,
        borderRadius: 4,
      }}
    >
      {attachments.length > 0 && (
        <Group gap="xs">
          {attachments.map((attachment, index) => (
            <Box key={attachment.preview} pos="relative">
              <Image
                src={attachment.preview}
                alt={attachment.file.name}
                h={64}
                w={64}
                fit="cover"
                radius="sm"
              />
              <CloseButton
                size="xs"
                variant="filled"
                aria-label={`Remove ${attachment.file.name}`}
                onClick={() => removeAt(index)}
                pos="absolute"
                top={-6}
                right={-6}
              />
            </Box>
          ))}
        </Group>
      )}

      <Group align="flex-end" gap="sm">
        <input
          ref={fileInput}
          type="file"
          accept={ACCEPTED.join(",")}
          multiple
          hidden
          onChange={(e) => {
            addFiles(Array.from(e.currentTarget.files ?? []));
            // Reset, or picking the same file twice in a row fires nothing.
            e.currentTarget.value = "";
          }}
        />
        <Button
          variant="default"
          aria-label="Attach a screenshot"
          disabled={sending || attachments.length >= MAX_SCREENSHOTS}
          onClick={() => fileInput.current?.click()}
        >
          Attach
        </Button>
        <Textarea
          placeholder="Message the session as the agent… (Ctrl+Enter to send, paste to attach a screenshot)"
          value={draft}
          onChange={(e) => setDraft(e.currentTarget.value)}
          onPaste={(e) => {
            const files = Array.from(e.clipboardData.files);
            if (files.length > 0) {
              // A pasted image is an attachment, not the filename as text.
              e.preventDefault();
              addFiles(files);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) {
              e.preventDefault();
              handleSend();
            }
          }}
          autosize
          minRows={2}
          maxRows={8}
          disabled={sending}
          style={{ flex: 1 }}
        />
        <Button onClick={handleSend} loading={sending} disabled={empty}>
          Send
        </Button>
      </Group>

      <Text size="xs" c="dimmed">
        {sending
          ? "Claude is working on your message — the transcript above updates live."
          : `Paste, drop or pick up to ${MAX_SCREENSHOTS} images — they are saved in the container and the agent is told to read them.`}
      </Text>
    </Stack>
  );
}
