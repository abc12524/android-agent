# Graph Report - android-agent-ref  (2026-07-17)

## Corpus Check
- 40 files · ~17,564 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 276 nodes · 342 edges · 33 communities (15 shown, 18 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 12 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `d2bcc4d6`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_Community 0|Community 0]]
- [[_COMMUNITY_Community 1|Community 1]]
- [[_COMMUNITY_Community 2|Community 2]]
- [[_COMMUNITY_Community 3|Community 3]]
- [[_COMMUNITY_Community 4|Community 4]]
- [[_COMMUNITY_Community 5|Community 5]]
- [[_COMMUNITY_Community 6|Community 6]]
- [[_COMMUNITY_Community 7|Community 7]]
- [[_COMMUNITY_Community 8|Community 8]]
- [[_COMMUNITY_Community 9|Community 9]]
- [[_COMMUNITY_Community 10|Community 10]]
- [[_COMMUNITY_Community 11|Community 11]]
- [[_COMMUNITY_Community 12|Community 12]]
- [[_COMMUNITY_Community 13|Community 13]]
- [[_COMMUNITY_Community 14|Community 14]]
- [[_COMMUNITY_Community 15|Community 15]]
- [[_COMMUNITY_Community 16|Community 16]]
- [[_COMMUNITY_Community 17|Community 17]]
- [[_COMMUNITY_Community 18|Community 18]]
- [[_COMMUNITY_Community 19|Community 19]]
- [[_COMMUNITY_Community 20|Community 20]]
- [[_COMMUNITY_Community 21|Community 21]]
- [[_COMMUNITY_Community 22|Community 22]]
- [[_COMMUNITY_Community 23|Community 23]]
- [[_COMMUNITY_Community 24|Community 24]]
- [[_COMMUNITY_Community 25|Community 25]]
- [[_COMMUNITY_Community 26|Community 26]]
- [[_COMMUNITY_Community 27|Community 27]]
- [[_COMMUNITY_Community 28|Community 28]]

## God Nodes (most connected - your core abstractions)
1. `OpenVikingClient` - 18 edges
2. `MessageDao` - 15 edges
3. `PythonManager` - 14 edges
4. `SessionDao` - 10 edges
5. `ChatViewModel` - 9 edges
6. `ChatEngine` - 8 edges
7. `ForegroundService` - 7 edges
8. `SensorTool` - 7 edges
9. `AppUpdater` - 7 edges
10. `ChatScreen()` - 7 edges

## Surprising Connections (you probably didn't know these)
- `MessageBubble()` --calls--> `MarkdownText()`  [INFERRED]
  app/src/main/java/com/androidagent/ui/chat/ChatScreen.kt → app/src/main/java/com/androidagent/ui/chat/MarkdownText.kt

## Communities (33 total, 18 thin omitted)

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (9): OpenVikingAddMessageTool, OpenVikingCommitSessionTool, OpenVikingCreateSessionTool, OpenVikingDeleteFileTool, OpenVikingListDirTool, OpenVikingReadTool, OpenVikingRememberTool, OpenVikingSearchTool (+1 more)

### Community 2 - "Community 2"
Cohesion: 0.12
Nodes (16): 🤖 AI 对话, Android Agent, code:bash (# 克隆仓库), code:bash (# gradle-wrapper.properties), License, 使用, 功能, 国内网络环境 (+8 more)

### Community 3 - "Community 3"
Cohesion: 0.18
Nodes (3): InitStatus, ProcessResult, PythonManager

### Community 5 - "Community 5"
Cohesion: 0.17
Nodes (4): ForegroundService, start(), releaseCurrent(), SoundTool

### Community 6 - "Community 6"
Cohesion: 0.16
Nodes (10): MainActivity, Available, Checking, Downloading, Error, Idle, Latest, SettingsScreen() (+2 more)

### Community 7 - "Community 7"
Cohesion: 0.21
Nodes (4): ChatEngine, ChatResult, ChatSession, Message

### Community 8 - "Community 8"
Cohesion: 0.24
Nodes (10): ChatMessage, ChatRequest, ChatResponse, Choice, DeepSeekClient, ToolCall, ToolDefinition, ToolFunction (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.36
Nodes (11): ChatScreen(), fmtSessionTime(), fmtTime(), groupSessions(), isYest(), MessageBubble(), sameDay(), saveFileToAppDir() (+3 more)

### Community 10 - "Community 10"
Cohesion: 0.27
Nodes (3): SCPTool, normalizePem(), SSHTool

### Community 14 - "Community 14"
Cohesion: 0.46
Nodes (7): Block, heading(), inlineText(), MarkdownText(), parseInline(), renderLine(), splitCodeBlocks()

### Community 19 - "Community 19"
Cohesion: 0.60
Nodes (3): ensureChannel(), NotificationTool, postNotification()

## Knowledge Gaps
- **20 isolated node(s):** `InitStatus`, `ToolDefinition`, `ToolFunctionDef`, `ChatUiState`, `UpdateState` (+15 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ChatScreen()` connect `Community 9` to `Community 0`, `Community 6`?**
  _High betweenness centrality (0.019) - this node is a cross-community bridge._
- **Why does `SettingsScreen()` connect `Community 6` to `Community 0`?**
  _High betweenness centrality (0.011) - this node is a cross-community bridge._
- **What connects `InitStatus`, `ToolDefinition`, `ToolFunctionDef` to the rest of the system?**
  _20 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.10526315789473684 - nodes in this community are weakly interconnected._
- **Should `Community 2` be split into smaller, more focused modules?**
  _Cohesion score 0.11764705882352941 - nodes in this community are weakly interconnected._
- **Should `Community 4` be split into smaller, more focused modules?**
  _Cohesion score 0.125 - nodes in this community are weakly interconnected._