export interface TextBlock {
  type: "text";
  text: string;
}

export interface ThinkingBlock {
  type: "thinking";
  thinking: string;
  signature?: string;
}

export interface ToolUseBlock {
  type: "tool_use";
  id: string;
  name: string;
  input: Record<string, unknown>;
}

export type ContentBlock = TextBlock | ThinkingBlock | ToolUseBlock;

export interface ToolResultContent {
  type: "tool_result";
  tool_use_id: string;
  content: string | Array<{ type: string; text?: string }>;
  is_error?: boolean;
}

export interface AssistantMessage {
  type: "assistant";
  uuid: string;
  parentUuid: string | null;
  timestamp: string;
  isSidechain: boolean;
  isMeta?: boolean;
  sessionId: string;
  cwd?: string;
  version?: string;
  gitBranch?: string;
  slug?: string;
  message: {
    role: "assistant";
    content: ContentBlock[];
    model?: string;
    usage?: {
      input_tokens: number;
      output_tokens: number;
    };
    stop_reason?: string;
  };
}

export interface UserMessage {
  type: "user";
  uuid: string;
  parentUuid: string | null;
  timestamp: string;
  isSidechain: boolean;
  isMeta?: boolean;
  sessionId: string;
  cwd?: string;
  version?: string;
  gitBranch?: string;
  slug?: string;
  message: {
    role: "user";
    content: string | Array<ToolResultContent | TextBlock>;
  };
}

export interface SystemMessage {
  type: "system";
  uuid: string;
  parentUuid: string | null;
  timestamp: string;
  isSidechain: boolean;
  sessionId: string;
  subtype?: string;
  level?: string;
}

export type SessionMessage = UserMessage | AssistantMessage | SystemMessage;
