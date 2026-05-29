import React from 'react';
import { createRoot } from 'react-dom/client';
import {
  BarChart3,
  Bot,
  CheckCircle2,
  Database,
  FileText,
  FlaskConical,
  Gauge,
  Loader2,
  LogOut,
  MessageSquare,
  Moon,
  Plus,
  RefreshCcw,
  Save,
  Search,
  Send,
  SlidersHorizontal,
  Sun,
  Trash2,
  Upload,
  User,
  XCircle,
} from 'lucide-react';
import './styles.css';

const API_BASE = '';

function createId() {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

function normalizeSession(session) {
  if (typeof session === 'string') {
    return {
      chatId: session,
      title: session.slice(-8).toUpperCase(),
    };
  }
  return {
    ...session,
    title: session?.title || session?.chatId?.slice(-8).toUpperCase() || '新会话',
  };
}

function formatTime(value) {
  if (!value) return '';
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
}

function getFileSize(size) {
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
}

function percent(value) {
  return `${Number(value || 0).toFixed(2)}%`;
}

const RAG_SETTING_HELP = {
  chunkSize: '单个知识片段的目标最大长度；结构化切分仍会用它控制片段上限。',
  chunkOverlap: '超长段落滑窗切分时的重叠长度，用来避免信息被切断。',
  topK: '向量检索阶段召回的核心片段数量，值越大越不容易漏，但更慢。',
  similarityThreshold: '相似度过滤阈值，越高越严格，越低越宽松。',
  rerankTopN: '大模型重排序后保留给最终回答的来源片段数量。',
  rerankEnabled: '开启后会让大模型再次筛选来源，通常更准，但会增加耗时和 token。',
};

// 将表单中的字符串数字统一转成真实数值，方便判断是否存在未保存修改。
function normalizeSettings(settings) {
  if (!settings) return null;
  return {
    chunkSize: Number(settings.chunkSize),
    chunkOverlap: Number(settings.chunkOverlap),
    topK: Number(settings.topK),
    similarityThreshold: Number(settings.similarityThreshold),
    rerankTopN: Number(settings.rerankTopN),
    rerankEnabled: Boolean(settings.rerankEnabled),
  };
}

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    credentials: 'include',
    ...options,
  });
  const text = await response.text();

  if (!response.ok) {
    let message = '请求失败';
    try {
      const error = text ? JSON.parse(text) : null;
      message = error.message || message;
    } catch {
      message = text || message;
    }
    const error = new Error(response.status === 403 ? '无权限访问' : message);
    error.status = response.status;
    throw error;
  }

  if (response.status === 204) return null;
  return text ? JSON.parse(text) : null;
}

function App() {
  const [activeView, setActiveView] = React.useState('chat');
  const [theme, setTheme] = React.useState('dark');
  const [authChecked, setAuthChecked] = React.useState(false);
  const [authUser, setAuthUser] = React.useState(null);
  const [loginForm, setLoginForm] = React.useState({ username: '', password: '' });
  const [loggingIn, setLoggingIn] = React.useState(false);
  const [documents, setDocuments] = React.useState([]);
  const [sessions, setSessions] = React.useState([]);
  const [settings, setSettings] = React.useState(null);
  const [settingsDraft, setSettingsDraft] = React.useState(null);
  const [activeChatId, setActiveChatId] = React.useState(createId());
  const [messages, setMessages] = React.useState([]);
  const [question, setQuestion] = React.useState('');
  const [debugQuestion, setDebugQuestion] = React.useState('');
  const [debugResult, setDebugResult] = React.useState(null);
  const [evaluationCases, setEvaluationCases] = React.useState([]);
  const [evaluationRuns, setEvaluationRuns] = React.useState([]);
  const [newCase, setNewCase] = React.useState({ question: '', expectedDocument: '', referenceAnswer: '', expectedKeywords: '' });
  const [uploading, setUploading] = React.useState(false);
  const [asking, setAsking] = React.useState(false);
  const [savingSettings, setSavingSettings] = React.useState(false);
  const [debugging, setDebugging] = React.useState(false);
  const [evaluating, setEvaluating] = React.useState(false);
  const [error, setError] = React.useState('');
  const isAdmin = authUser?.role === 'ADMIN';

  const loadDocuments = React.useCallback(async () => {
    const data = await fetchJson(`${API_BASE}/api/knowledge/documents`);
    setDocuments(data || []);
  }, []);

  const loadSessions = React.useCallback(async () => {
    const data = await fetchJson(`${API_BASE}/api/history/sessions`);
    setSessions((data || []).map(normalizeSession));
  }, []);

  const loadSettings = React.useCallback(async () => {
    const data = await fetchJson(`${API_BASE}/api/rag/settings`);
    setSettings(data);
    setSettingsDraft(data);
  }, []);

  const loadEvaluation = React.useCallback(async () => {
    const [cases, runs] = await Promise.all([
      fetchJson(`${API_BASE}/api/evaluation/cases`),
      fetchJson(`${API_BASE}/api/evaluation/runs`),
    ]);
    setEvaluationCases(cases || []);
    setEvaluationRuns(runs || []);
  }, []);

  const loadMessages = React.useCallback(async (chatId) => {
    const data = await fetchJson(`${API_BASE}/api/history/messages?chatId=${encodeURIComponent(chatId)}`);
    setMessages((data || []).map((item) => ({ ...item, sources: [] })));
  }, []);

  const handleApiError = React.useCallback((err) => {
    if (err.status === 401) {
      setAuthUser(null);
      setMessages([]);
      setSessions([]);
      setError('登录已过期，请重新登录');
      return;
    }
    setError(err.status === 403 ? '无权限访问' : err.message);
  }, []);

  React.useEffect(() => {
    fetchJson(`${API_BASE}/api/auth/me`)
      .then((user) => setAuthUser(user))
      .catch((err) => {
        if (err.status !== 401) {
          handleApiError(err);
        }
      })
      .finally(() => setAuthChecked(true));
  }, []);

  React.useEffect(() => {
    if (!authChecked || !authUser) return;
    if (authUser.role !== 'ADMIN' && activeView !== 'chat') {
      setActiveView('chat');
    }
    const adminLoads = authUser.role === 'ADMIN'
      ? [loadDocuments(), loadSettings(), loadEvaluation()]
      : [Promise.resolve(setDocuments([])), Promise.resolve(setSettings(null)), Promise.resolve(setSettingsDraft(null)), Promise.resolve(setEvaluationCases([])), Promise.resolve(setEvaluationRuns([]))];
    Promise.all([loadSessions(), ...adminLoads]).catch(handleApiError);
  }, [authChecked, authUser, activeView, loadDocuments, loadSessions, loadSettings, loadEvaluation, handleApiError]);

  async function handleLogin(event) {
    event.preventDefault();
    if (!loginForm.username.trim() || !loginForm.password || loggingIn) return;

    setLoggingIn(true);
    setError('');
    try {
      const user = await fetchJson(`${API_BASE}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(loginForm),
      });
      setAuthUser(user);
      setLoginForm({ username: '', password: '' });
      setActiveView('chat');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoggingIn(false);
    }
  }

  async function handleLogout() {
    setError('');
    try {
      await fetchJson(`${API_BASE}/api/auth/logout`, { method: 'POST' });
    } catch {
      // 即使服务端清理失败，也清掉本地登录态，避免用户继续误操作。
    }
    setAuthUser(null);
    setSessions([]);
    setMessages([]);
    setDocuments([]);
    setActiveChatId(createId());
  }

  async function handleUpload(event) {
    const files = Array.from(event.target.files || []);
    if (files.length === 0) return;

    setUploading(true);
    setError('');
    try {
      for (const file of files) {
        const formData = new FormData();
        formData.append('file', file);
        await fetchJson(`${API_BASE}/api/knowledge/documents`, {
          method: 'POST',
          body: formData,
        });
      }
      await loadDocuments();
    } catch (err) {
      handleApiError(err);
    } finally {
      setUploading(false);
      event.target.value = '';
    }
  }

  async function handleDeleteDocument(id, filename) {
    const allowed = window.confirm(`确认从知识库移除「${filename}」吗？该操作会清理向量索引，但不会物理删除服务器上的原始文件。`);
    if (!allowed) return;

    setError('');
    try {
      await fetchJson(`${API_BASE}/api/knowledge/documents/${id}`, { method: 'DELETE' });
      await loadDocuments();
    } catch (err) {
      handleApiError(err);
    }
  }

  async function handleAsk() {
    const text = question.trim();
    if (!text || asking) return;

    const chatId = activeChatId || createId();
    setActiveChatId(chatId);
    setQuestion('');
    setAsking(true);
    setError('');

    const userMessage = {
      id: createId(),
      chatId,
      role: 'user',
      content: text,
      createTime: new Date().toISOString(),
      sources: [],
    };
    const assistantDraft = {
      id: createId(),
      chatId,
      role: 'assistant',
      content: '',
      createTime: new Date().toISOString(),
      sources: [],
    };
    setMessages((prev) => [...prev, userMessage, assistantDraft]);

    try {
      const data = await fetchJson(`${API_BASE}/api/chat/rag`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chatId, question: text }),
      });
      setMessages((prev) =>
        prev.map((message) =>
          message.id === assistantDraft.id
            ? { ...message, content: data.answer, sources: data.sources || [] }
            : message
        )
      );
      await loadSessions();
    } catch (err) {
      setMessages((prev) =>
        prev.map((message) =>
          message.id === assistantDraft.id
            ? { ...message, content: `请求失败：${err.message}` }
            : message
        )
      );
      handleApiError(err);
    } finally {
      setAsking(false);
    }
  }

  async function handleSelectSession(chatId) {
    setActiveView('chat');
    setActiveChatId(chatId);
    setError('');
    try {
      await loadMessages(chatId);
    } catch (err) {
      handleApiError(err);
    }
  }

  async function handleDeleteSession(chatId) {
    const allowed = window.confirm('确认删除该会话历史吗？');
    if (!allowed) return;

    setError('');
    try {
      await fetchJson(`${API_BASE}/api/history/session?chatId=${encodeURIComponent(chatId)}`, { method: 'DELETE' });
      if (activeChatId === chatId) {
        setActiveChatId(createId());
        setMessages([]);
      }
      await loadSessions();
    } catch (err) {
      handleApiError(err);
    }
  }

  async function handleSaveSettings() {
    setSavingSettings(true);
    setError('');
    try {
      const payload = {
        ...settingsDraft,
        chunkSize: Number(settingsDraft.chunkSize),
        chunkOverlap: Number(settingsDraft.chunkOverlap),
        topK: Number(settingsDraft.topK),
        similarityThreshold: Number(settingsDraft.similarityThreshold),
        rerankTopN: Number(settingsDraft.rerankTopN),
        rerankEnabled: Boolean(settingsDraft.rerankEnabled),
      };
      const data = await fetchJson(`${API_BASE}/api/rag/settings`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });
      setSettings(data);
      setSettingsDraft(data);
    } catch (err) {
      handleApiError(err);
    } finally {
      setSavingSettings(false);
    }
  }

  async function handleDebug() {
    const text = debugQuestion.trim();
    if (!text || debugging) return;

    setDebugging(true);
    setError('');
    try {
      const data = await fetchJson(`${API_BASE}/api/chat/debug`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: text }),
      });
      setDebugResult(data);
    } catch (err) {
      handleApiError(err);
    } finally {
      setDebugging(false);
    }
  }

  async function handleCreateCase() {
    if (!newCase.question.trim()) return;
    setError('');
    try {
      await fetchJson(`${API_BASE}/api/evaluation/cases`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newCase),
      });
      setNewCase({ question: '', expectedDocument: '', referenceAnswer: '', expectedKeywords: '' });
      await loadEvaluation();
    } catch (err) {
      handleApiError(err);
    }
  }

  async function handleDeleteCase(id, questionText) {
    const allowed = window.confirm(`确认删除评测用例「${questionText}」吗？历史评测结果不会被删除。`);
    if (!allowed) return;

    setError('');
    try {
      await fetchJson(`${API_BASE}/api/evaluation/cases/${id}`, { method: 'DELETE' });
      await loadEvaluation();
    } catch (err) {
      handleApiError(err);
    }
  }

  async function handleRunEvaluation() {
    setEvaluating(true);
    setError('');
    try {
      await fetchJson(`${API_BASE}/api/evaluation/runs`, { method: 'POST' });
      await loadEvaluation();
    } catch (err) {
      handleApiError(err);
    } finally {
      setEvaluating(false);
    }
  }

  function startNewChat() {
    setActiveView('chat');
    setActiveChatId(createId());
    setMessages([]);
    setQuestion('');
  }

  // Theme state lives on the shell so CSS can fully invert the black and white variants.
  function toggleTheme() {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'));
  }

  if (!authChecked) {
    return (
      <div className="app-shell auth-shell" data-theme={theme}>
        <div className="login-card">
          <Loader2 className="spin" size={28} />
          <span>正在检查登录状态...</span>
        </div>
      </div>
    );
  }

  if (!authUser) {
    return (
      <LoginPage
        theme={theme}
        loginForm={loginForm}
        setLoginForm={setLoginForm}
        loggingIn={loggingIn}
        error={error}
        onLogin={handleLogin}
        onToggleTheme={toggleTheme}
      />
    );
  }

  return (
    <div className="app-shell" data-theme={theme}>
      <aside className="sidebar">
        <div className="brand-row">
          <Database size={24} />
          <div>
            <h1>RAG</h1>
            <span>本地知识库问答</span>
          </div>
        </div>

        {isAdmin && (
          <section className="side-section">
            <div className="section-title">
              <FileText size={16} />
              <span>知识库</span>
            </div>
            <label className="upload-button">
              {uploading ? <Loader2 className="spin" size={18} /> : <Upload size={18} />}
              <span>{uploading ? '导入中' : '上传文档'}</span>
              <input
                type="file"
                accept=".pdf,.txt,.md,.markdown,.docx,.xlsx,.csv,.pptx"
                multiple
                onChange={handleUpload}
                disabled={uploading}
              />
            </label>
            <button className="ghost-button" onClick={loadDocuments}>
              <RefreshCcw size={16} />
              <span>刷新列表</span>
            </button>
          </section>
        )}

        <section className="side-section">
          <div className="section-title">
            <Gauge size={16} />
            <span>质量工具</span>
          </div>
          <button className={`ghost-button ${activeView === 'chat' ? 'active' : ''}`} onClick={startNewChat}>
            <MessageSquare size={16} />
            <span>问答</span>
          </button>
          {isAdmin && (
            <>
              <button className={`ghost-button ${activeView === 'debug' ? 'active' : ''}`} onClick={() => setActiveView('debug')}>
                <SlidersHorizontal size={16} />
                <span>检索调试</span>
              </button>
              <button className={`ghost-button ${activeView === 'evaluation' ? 'active' : ''}`} onClick={() => setActiveView('evaluation')}>
                <FlaskConical size={16} />
                <span>评测体系</span>
              </button>
            </>
          )}
        </section>

        <section className="side-section session-section">
          <div className="section-title">
            <MessageSquare size={16} />
            <span>历史会话</span>
          </div>
          <button className="ghost-button" onClick={startNewChat}>
            <Plus size={16} />
            <span>新会话</span>
          </button>
          <div className="session-list">
            {sessions.map((session) => (
              <button
                key={session.chatId}
                className={`session-item ${session.chatId === activeChatId ? 'active' : ''}`}
                onClick={() => handleSelectSession(session.chatId)}
                title={session.title}
              >
                <MessageSquare size={15} />
                <span>{session.title}</span>
                <Trash2
                  size={14}
                  onClick={(event) => {
                    event.stopPropagation();
                    handleDeleteSession(session.chatId);
                  }}
                />
              </button>
            ))}
          </div>
        </section>

        <div className="system-status">
          <span>SYSTEM STATUS</span>
          <strong>ONLINE</strong>
          <i aria-hidden="true" />
        </div>
      </aside>

      <main className="workspace">
        <Header
          activeView={activeView}
          readyCount={isAdmin ? documents.filter((doc) => doc.status === 'READY').length : null}
          theme={theme}
          user={authUser}
          onToggleTheme={toggleTheme}
          onLogout={handleLogout}
        />

        {error && <div className="error-banner">{error}</div>}

        <div key={activeView} className="view-transition">
          {activeView === 'chat' && (
            <ChatWorkspace
              documents={documents}
              isAdmin={isAdmin}
              messages={messages}
              question={question}
              asking={asking}
              setQuestion={setQuestion}
              handleAsk={handleAsk}
              handleDeleteDocument={handleDeleteDocument}
            />
          )}

          {isAdmin && activeView === 'debug' && (
            <DebugWorkspace
              settings={settings}
              settingsDraft={settingsDraft}
              setSettingsDraft={setSettingsDraft}
              savingSettings={savingSettings}
              handleSaveSettings={handleSaveSettings}
              debugQuestion={debugQuestion}
              setDebugQuestion={setDebugQuestion}
              debugging={debugging}
              handleDebug={handleDebug}
              debugResult={debugResult}
            />
          )}

          {isAdmin && activeView === 'evaluation' && (
            <EvaluationWorkspace
              cases={evaluationCases}
              runs={evaluationRuns}
              newCase={newCase}
              setNewCase={setNewCase}
              handleCreateCase={handleCreateCase}
              handleDeleteCase={handleDeleteCase}
              handleRunEvaluation={handleRunEvaluation}
              evaluating={evaluating}
            />
          )}
        </div>
      </main>
    </div>
  );
}

function LoginPage({ theme, loginForm, setLoginForm, loggingIn, error, onLogin, onToggleTheme }) {
  return (
    <div className="app-shell auth-shell" data-theme={theme}>
      <button className="theme-toggle login-theme" onClick={onToggleTheme} type="button" title="Toggle theme">
        {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
        <span>{theme === 'dark' ? 'LIGHT' : 'DARK'}</span>
      </button>
      <section className="login-card">
        <div className="brand-row login-brand">
          <Database size={28} />
          <div>
            <h1>RAG</h1>
            <span>本地知识库问答</span>
          </div>
        </div>
        <form className="login-form" onSubmit={onLogin}>
          <div>
            <h2>登录</h2>
            <p>请输入账号密码访问 RAG Demo。</p>
          </div>
          {error && <div className="error-banner">{error}</div>}
          <label>
            <span>用户名</span>
            <input
              value={loginForm.username}
              onChange={(event) => setLoginForm({ ...loginForm, username: event.target.value })}
              autoComplete="username"
              autoFocus
            />
          </label>
          <label>
            <span>密码</span>
            <input
              type="password"
              value={loginForm.password}
              onChange={(event) => setLoginForm({ ...loginForm, password: event.target.value })}
              autoComplete="current-password"
            />
          </label>
          <button className="primary-button login-submit" type="submit" disabled={loggingIn || !loginForm.username.trim() || !loginForm.password}>
            {loggingIn ? <Loader2 className="spin" size={18} /> : <User size={18} />}
            <span>{loggingIn ? '登录中' : '进入系统'}</span>
          </button>
        </form>
      </section>
    </div>
  );
}

function Header({ activeView, readyCount, theme, user, onToggleTheme, onLogout }) {
  const copy = {
    chat: ['知识库检索增强问答', '上传资料后提问，回答会附带命中的来源片段。'],
    debug: ['检索调试与参数配置', '调整切块、召回和重排序参数，观察候选片段与最终答案。'],
    evaluation: ['RAG 评测体系', '用固定问题集对比命中率、来源覆盖率和答案关键词覆盖率。'],
  }[activeView];

  return (
    <header className="topbar">
      <div>
        <h2>{copy[0]}</h2>
        <p>{copy[1]}</p>
      </div>
      <div className="topbar-actions">
        {readyCount !== null && (
          <div className="status-pill">
            <Search size={16} />
            <span>{readyCount} 个可检索文档</span>
          </div>
        )}
        <div className="user-pill">
          <User size={16} />
          <span>{user.username}</span>
          <strong>{user.role}</strong>
        </div>
        <button className="theme-toggle" onClick={onToggleTheme} type="button" title="Toggle theme">
          {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
          <span>{theme === 'dark' ? 'LIGHT' : 'DARK'}</span>
        </button>
        <button className="theme-toggle" onClick={onLogout} type="button" title="退出登录">
          <LogOut size={16} />
          <span>退出</span>
        </button>
      </div>
    </header>
  );
}

function ChatWorkspace({ documents, isAdmin, messages, question, asking, setQuestion, handleAsk, handleDeleteDocument }) {
  return (
    <div className={isAdmin ? 'content-grid' : 'content-grid chat-only-grid'}>
      {isAdmin && <section className="document-panel">
        <div className="panel-head">
          <h3>文档列表</h3>
          <span>{documents.length} 个文件</span>
        </div>
        <div className="document-list">
          {documents.length === 0 && <div className="empty-state">暂无文档，请先上传知识库文件。</div>}
          {documents.map((doc) => (
            <article key={doc.id} className="document-item">
              <div className="file-icon">
                <FileText size={20} />
              </div>
              <div className="file-info">
                <strong>{doc.filename}</strong>
                <span>{getFileSize(doc.fileSize)} · {doc.chunkCount} 个片段 · {formatTime(doc.createTime)}</span>
                {doc.errorMessage && <em>{doc.errorMessage}</em>}
              </div>
              <span className={`doc-status ${doc.status.toLowerCase()}`}>{doc.status}</span>
              <button className="icon-button danger" onClick={() => handleDeleteDocument(doc.id, doc.filename)}>
                <Trash2 size={16} />
              </button>
            </article>
          ))}
        </div>
      </section>}

      <section className="chat-panel">
        <div className="message-list">
          {messages.length === 0 && (
            <div className="start-state">
              <Bot size={34} />
              <h3>开始一次知识库问答</h3>
              <p>问题会先检索 Qdrant，再结合来源片段生成回答。</p>
            </div>
          )}
          {messages.map((message) => (
            <MessageBubble key={message.id} message={message} />
          ))}
        </div>

        <div className="composer">
          <textarea
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                handleAsk();
              }
            }}
            placeholder="输入你的问题..."
            rows={2}
          />
          <button className="send-button" onClick={handleAsk} disabled={!question.trim() || asking}>
            {asking ? <Loader2 className="spin" size={20} /> : <Send size={20} />}
          </button>
        </div>
      </section>
    </div>
  );
}

function DebugWorkspace({
  settings,
  settingsDraft,
  setSettingsDraft,
  savingSettings,
  handleSaveSettings,
  debugQuestion,
  setDebugQuestion,
  debugging,
  handleDebug,
  debugResult,
}) {
  if (!settingsDraft) {
    return <div className="tool-page"><div className="empty-state">正在读取 RAG 参数...</div></div>;
  }
  const settingsChanged = JSON.stringify(normalizeSettings(settings)) !== JSON.stringify(normalizeSettings(settingsDraft));

  return (
    <div className="tool-page debug-page">
      <section className="tool-panel evaluation-overview">
        <div className="panel-head">
          <h3>运行时参数</h3>
          <div className="settings-actions">
            <span className={settingsChanged ? 'dirty-badge active' : 'dirty-badge'}>
              {settingsChanged ? '有未保存修改' : settings?.rerankEnabled ? '重排序已启用' : '重排序未启用'}
            </span>
            <button className="primary-button compact-button" onClick={handleSaveSettings} disabled={savingSettings || !settingsChanged}>
              {savingSettings ? <Loader2 className="spin" size={16} /> : <Save size={16} />}
              <span>保存</span>
            </button>
          </div>
        </div>
        <div className="settings-grid">
          <NumberField label="chunkSize" description={RAG_SETTING_HELP.chunkSize} value={settingsDraft.chunkSize} min={200} max={4000} onChange={(value) => setSettingsDraft({ ...settingsDraft, chunkSize: value })} />
          <NumberField label="chunkOverlap" description={RAG_SETTING_HELP.chunkOverlap} value={settingsDraft.chunkOverlap} min={0} max={3999} onChange={(value) => setSettingsDraft({ ...settingsDraft, chunkOverlap: value })} />
          <NumberField label="topK" description={RAG_SETTING_HELP.topK} value={settingsDraft.topK} min={1} max={20} onChange={(value) => setSettingsDraft({ ...settingsDraft, topK: value })} />
          <NumberField label="similarityThreshold" description={RAG_SETTING_HELP.similarityThreshold} value={settingsDraft.similarityThreshold} min={0} max={1} step={0.01} onChange={(value) => setSettingsDraft({ ...settingsDraft, similarityThreshold: value })} />
          <NumberField label="rerankTopN" description={RAG_SETTING_HELP.rerankTopN} value={settingsDraft.rerankTopN} min={1} max={20} onChange={(value) => setSettingsDraft({ ...settingsDraft, rerankTopN: value })} />
          <label className="toggle-field">
            <span>rerankEnabled</span>
            <small className="field-help">{RAG_SETTING_HELP.rerankEnabled}</small>
            <input type="checkbox" checked={settingsDraft.rerankEnabled} onChange={(event) => setSettingsDraft({ ...settingsDraft, rerankEnabled: event.target.checked })} />
          </label>
        </div>
        {/*<button className="primary-button" onClick={handleSaveSettings} disabled={savingSettings || !settingsChanged}>*/}
        {/*  {savingSettings ? <Loader2 className="spin" size={16} /> : <Save size={16} />}*/}
        {/*  <span>{settingsChanged ? '保存运行时参数到数据库' : '参数已是数据库中的最新值'}</span>*/}
        {/*</button>*/}
      </section>

      <section className="tool-panel evaluation-form-panel">
        <div className="panel-head">
          <h3>检索调试</h3>
          <span>候选片段 + 重排序结果</span>
        </div>
        <div className="debug-box">
          <textarea value={debugQuestion} onChange={(event) => setDebugQuestion(event.target.value)} rows={3} />
          <button className="primary-button" onClick={handleDebug} disabled={!debugQuestion.trim() || debugging}>
            {debugging ? <Loader2 className="spin" size={16} /> : <Search size={16} />}
            <span>运行调试</span>
          </button>
        </div>
        {debugResult && <DebugResult result={debugResult} />}
      </section>
    </div>
  );
}

function EvaluationWorkspace({ cases, runs, newCase, setNewCase, handleCreateCase, handleDeleteCase, handleRunEvaluation, evaluating }) {
  const latestRun = runs[0];
  return (
    <div className={`tool-page evaluation-page ${latestRun ? 'has-results' : 'no-results'}`}>
      <section className="tool-panel evaluation-overview">
        <div className="panel-head">
          <h3>评测总览</h3>
          <span>{cases.length} 个用例</span>
        </div>
        <div className="metric-row">
          <Metric icon={<Search size={18} />} label="命中率" value={latestRun ? percent(latestRun.hitRate) : '暂无'} />
          <Metric icon={<TargetIcon />} label="来源覆盖率" value={latestRun ? percent(latestRun.sourceCoverageRate) : '暂无'} />
          <Metric icon={<BarChart3 size={18} />} label="关键词覆盖率" value={latestRun ? percent(latestRun.answerKeywordRate) : '暂无'} />
        </div>
        <button className="primary-button" onClick={handleRunEvaluation} disabled={evaluating || cases.length === 0}>
          {evaluating ? <Loader2 className="spin" size={16} /> : <FlaskConical size={16} />}
          <span>运行评测</span>
        </button>
      </section>

      <section className="tool-panel evaluation-form-panel">
        <div className="panel-head">
          <h3>新增评测问题</h3>
          <span>逗号分隔关键词</span>
        </div>
        <div className="case-form">
          <input value={newCase.question} onChange={(event) => setNewCase({ ...newCase, question: event.target.value })} placeholder="问题" />
          <input value={newCase.expectedDocument} onChange={(event) => setNewCase({ ...newCase, expectedDocument: event.target.value })} placeholder="期望文件名，如 test-rag-knowledge.txt" />
          <input value={newCase.expectedKeywords} onChange={(event) => setNewCase({ ...newCase, expectedKeywords: event.target.value })} placeholder="期望关键词，如 6333,6334" />
          <textarea value={newCase.referenceAnswer} onChange={(event) => setNewCase({ ...newCase, referenceAnswer: event.target.value })} placeholder="参考答案" rows={3} />
          <button className="primary-button" onClick={handleCreateCase} disabled={!newCase.question.trim()}>
            <Plus size={16} />
            <span>新增用例</span>
          </button>
        </div>
      </section>

      <section className="tool-panel evaluation-cases-panel">
        <div className="panel-head">
          <h3>评测用例</h3>
          <span>{cases.length} 条</span>
        </div>
        <div className="case-list">
          {cases.map((item) => (
            <article className="case-item" key={item.id}>
              <div className="case-item-head">
                <strong>{item.question}</strong>
                <button className="icon-button danger" onClick={() => handleDeleteCase(item.id, item.question)} title="删除用例" type="button">
                  <Trash2 size={16} />
                </button>
              </div>
              <span>{item.expectedDocument || '未指定来源'} · {item.expectedKeywords || '未指定关键词'}</span>
              {item.referenceAnswer && <p>{item.referenceAnswer}</p>}
            </article>
          ))}
        </div>
      </section>

      {latestRun && (
        <section className="tool-panel evaluation-results-panel">
          <div className="panel-head">
            <h3>最近一次结果</h3>
            <span>{formatTime(latestRun.createTime)}</span>
          </div>
          <div className="result-list">
            {latestRun.results.map((item) => (
              <article className="result-item" key={item.caseId}>
                <div className="result-title">
                  {item.hit && item.sourceCovered && item.keywordMatched ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
                  <strong>{item.question}</strong>
                </div>
                <span>来源：{item.matchedSources || '无'} · 期望：{item.expectedDocument || '未指定'}</span>
                <p>{item.answer}</p>
              </article>
            ))}
          </div>
        </section>
      )}
    </div>
  );
}

function NumberField({ label, description, value, min, max, step = 1, onChange }) {
  return (
    <label className="number-field">
      <span>{label}</span>
      <input type="number" value={value} min={min} max={max} step={step} onChange={(event) => onChange(event.target.value)} />
      {description && <small className="field-help">{description}</small>}
    </label>
  );
}

function DebugResult({ result }) {
  return (
    <div className="debug-result">
      <div>
        <h4>最终答案</h4>
        <p>{result.answer}</p>
      </div>
      <SourceList title="重排序来源" sources={result.selectedSources || []} />
      <SourceList title="召回候选" sources={result.candidates || []} />
    </div>
  );
}

function SourceList({ title, sources }) {
  return (
    <div className="sources debug-sources">
      <div className="sources-title">{title}</div>
      {sources.length === 0 && <div className="empty-state compact">暂无片段</div>}
      {sources.map((source, index) => (
        <details key={`${source.chunkId}-${index}`} open={index < 2}>
          <summary>
            <span>{source.rank || index + 1}. {source.filename}</span>
            <small>{source.stage || 'source'} · {typeof source.score === 'number' ? source.score.toFixed(3) : 'N/A'}</small>
          </summary>
          {source.sectionTitle && <em>章节：{source.sectionTitle}</em>}
          <p>{source.snippet}</p>
          {source.reason && <em>{source.reason}</em>}
        </details>
      ))}
    </div>
  );
}

function MessageBubble({ message }) {
  const isUser = message.role === 'user';
  return (
    <article className={`message-row ${isUser ? 'user' : 'assistant'}`}>
      <div className="avatar">{isUser ? <User size={18} /> : <Bot size={18} />}</div>
      <div className="bubble-stack">
        <div className="message-bubble">
          {message.content || <span className="thinking">正在检索知识库并生成回答...</span>}
        </div>
        {!isUser && message.sources?.length > 0 && (
          <SourceList title="来源片段" sources={message.sources} />
        )}
      </div>
    </article>
  );
}

function Metric({ icon, label, value }) {
  return (
    <div className="metric-card">
      {icon}
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function TargetIcon() {
  return <CheckCircle2 size={18} />;
}

createRoot(document.getElementById('root')).render(<App />);

