"use client";

import { useState, useEffect } from "react";
import {
  Bell,
  CalendarDays,
  BookOpen,
  Users,
  Settings,
  Search,
  Plus,
  MoreVertical,
  CalendarClock,
  Languages,
  Heart,
  MessageSquare,
  Sun,
  Moon,
  Sparkles,
} from "lucide-react";

interface NoticeItem {
  id: string;
  kind: "general" | "cancelled" | "resource";
  title: string;
  body: string;
  translatedBody: string;
  dateStr: string;
  seenCount: number;
  likeCount: number;
  commentCount: number;
  avatars: { text: string; bg: string }[];
}

const INITIAL_NOTICES: NoticeItem[] = [
  {
    id: "n-1",
    kind: "general",
    title: "Update",
    body: "Matrix & linear algebra class will be at 12PM.\n\nDSA Lab class is in the routine today, but Mehedi sir did not pick up the call, I can't confirm.\nI hope the class will be there.",
    translatedBody: "ম্যাট্রিক্স এবং লিনিয়ার অ্যালজেব্রা ক্লাস দুপুর ১২টায় হবে।\n\nডিএসএ ল্যাব ক্লাস আজকের রুটিনে আছে, তবে মেহেদী স্যার ফোন রিসিভ করেননি, তাই নিশ্চিত হতে পারছি না।\nআশা করি ক্লাসটি হবে।",
    dateStr: "10 Oct 2026, 09:18",
    seenCount: 40,
    likeCount: 2,
    commentCount: 0,
    avatars: [
      { text: "M", bg: "#a855f7" },
      { text: "S", bg: "#06b6d4" },
      { text: "M", bg: "#3b82f6" },
      { text: "AI", bg: "#f43f5e" },
    ],
  },
  {
    id: "n-2",
    kind: "general",
    title: "Campus Safety Alert",
    body: "ক্যাম্পাসে মারামারি হইতেছে তোরা safe এ থাকিস।\nআপাতত ক্যাম্পাসে যাওয়ার প্রয়োজন নাই।",
    translatedBody: "A fight broke out on campus, please everyone stay safe.\nThere is no need to visit the campus right now.",
    dateStr: "08 Oct 2026, 20:34",
    seenCount: 35,
    likeCount: 4,
    commentCount: 0,
    avatars: [
      { text: "M", bg: "#f43f5e" },
      { text: "M", bg: "#3b82f6" },
      { text: "S", bg: "#10b981" },
      { text: "AI", bg: "#f59e0b" },
    ],
  },
  {
    id: "n-3",
    kind: "cancelled",
    title: "Class cancelled · Object Oriented Programming",
    body: "Class on 11 Oct 2026 is cancelled.",
    translatedBody: "১১ অক্টোবর ২০২৬-এর অবজেক্ট ওরিয়েন্টেড প্রোগ্রামিং ক্লাস বাতিল করা হয়েছে।",
    dateStr: "11 Oct 2026, 15:33",
    seenCount: 37,
    likeCount: 3,
    commentCount: 0,
    avatars: [
      { text: "M", bg: "#8b5cf6" },
      { text: "A", bg: "#ec4899" },
      { text: "R", bg: "#14b8a6" },
    ],
  },
  {
    id: "n-4",
    kind: "cancelled",
    title: "Class cancelled · Data Structures & Algorithms Lab",
    body: "Class on 11 Oct 2026 is cancelled.",
    translatedBody: "১১ অক্টোবর ২০২৬-এর ডাটা স্ট্রাকচার ও অ্যালগরিদম ল্যাব ক্লাস বাতিল করা হয়েছে।",
    dateStr: "11 Oct 2026, 13:09",
    seenCount: 39,
    likeCount: 1,
    commentCount: 0,
    avatars: [
      { text: "P", bg: "#3b82f6" },
      { text: "M", bg: "#a855f7" },
      { text: "S", bg: "#06b6d4" },
    ],
  },
  {
    id: "n-5",
    kind: "general",
    title: "Batch Fund Planning for Junior Orientation",
    body: "To prepare for the upcoming junior orientation program, please submit your batch fund contributions within this week. All expenditure receipts will be publicly posted.",
    translatedBody: "আসন্ন জুনিয়র ওরিয়েন্টেশন প্রোগ্রামের প্রস্তুতির জন্য দয়া করে এই সপ্তাহের মধ্যে আপনার ব্যাচ ফান্ডের চাঁদা জমা দিন। সমস্ত খরচের রশিদ প্রকাশ করা হবে।",
    dateStr: "07 Oct 2026, 16:45",
    seenCount: 42,
    likeCount: 5,
    commentCount: 2,
    avatars: [
      { text: "M", bg: "#3b82f6" },
      { text: "T", bg: "#10b981" },
      { text: "R", bg: "#f59e0b" },
    ],
  },
];

type ThemeChoice = "whatsapp" | "telegram" | "academic" | "original";

export default function NoticeTestPage() {
  const [theme, setTheme] = useState<ThemeChoice>("whatsapp");
  const [isDark, setIsDark] = useState<boolean>(false);
  const [notices, setNotices] = useState<NoticeItem[]>(INITIAL_NOTICES);
  const [likedMap, setLikedMap] = useState<Record<string, boolean>>({});
  const [translatedMap, setTranslatedMap] = useState<Record<string, boolean>>({});

  useEffect(() => {
    if (typeof window !== "undefined") {
      const params = new URLSearchParams(window.location.search);
      const urlMode = params.get("mode");
      const urlTheme = params.get("theme") as ThemeChoice | null;
      if (urlMode === "dark") setIsDark(true);
      if (urlTheme && ["whatsapp", "telegram", "academic", "original"].includes(urlTheme)) {
        setTheme(urlTheme);
      }
    }
  }, []);

  // Sync dark theme attribute on root
  useEffect(() => {
    document.documentElement.dataset.theme = isDark ? "dark" : "light";
  }, [isDark]);

  const toggleLike = (id: string) => {
    setLikedMap((prev) => {
      const isCurrentlyLiked = !!prev[id];
      const nextLiked = !isCurrentlyLiked;
      setNotices((current) =>
        current.map((n) =>
          n.id === id
            ? { ...n, likeCount: n.likeCount + (nextLiked ? 1 : -1) }
            : n
        )
      );
      return { ...prev, [id]: nextLiked };
    });
  };

  const toggleTranslate = (id: string) => {
    setTranslatedMap((prev) => ({
      ...prev,
      [id]: !prev[id],
    }));
  };

  return (
    <div className={`notices-screen theme-${theme} chat-wallpaper`}>
      {/* Floating Theme Switcher Test Toolbar */}
      <header className="theme-test-bar">
        <div className="theme-pills">
          <button
            onClick={() => setTheme("whatsapp")}
            className={`theme-pill-btn ${theme === "whatsapp" ? "active" : ""}`}
          >
            🟢 WhatsApp
          </button>
          <button
            onClick={() => setTheme("telegram")}
            className={`theme-pill-btn ${theme === "telegram" ? "active" : ""}`}
          >
            🔵 Telegram
          </button>
          <button
            onClick={() => setTheme("academic")}
            className={`theme-pill-btn ${theme === "academic" ? "active" : ""}`}
          >
            🟣 Academic
          </button>
          <button
            onClick={() => setTheme("original")}
            className={`theme-pill-btn ${theme === "original" ? "active" : ""}`}
          >
            ⚪ Current
          </button>
        </div>

        <button
          onClick={() => setIsDark(!isDark)}
          className="mode-toggle-btn"
          aria-label="Toggle dark mode"
        >
          {isDark ? <Sun size={13} /> : <Moon size={13} />}
          <span>{isDark ? "Light" : "Dark"}</span>
        </button>
      </header>

      {/* Main Notice Feed Container */}
      <main className="notice-container">
        {/* Page Heading */}
        <div className="page-heading">
          <div>
            <h1>Notices</h1>
            <p>24 updates · Pull to refresh</p>
          </div>
          <div className="header-actions">
            <button className="icon-btn" aria-label="Search notices">
              <Search size={18} />
            </button>
            <button className="primary-btn" aria-label="Post notice">
              <Plus size={16} strokeWidth={3} />
              <span>Post</span>
            </button>
          </div>
        </div>

        {/* Notices Cards List */}
        <div className="notice-list">
          {notices.map((n) => {
            const isTranslated = !!translatedMap[n.id];
            const isLiked = !!likedMap[n.id];
            const bodyContent = isTranslated ? n.translatedBody : n.body;

            return (
              <article key={n.id} className={`notice-card ${n.kind}`}>
                <span className="notice-accent" />

                <div className="notice-menu">
                  <button className="notice-menu-btn" aria-label="More options">
                    <MoreVertical size={18} />
                  </button>
                </div>

                <div className="notice-content">
                  <h2>{n.title}</h2>
                  <div className="notice-body">{bodyContent}</div>

                  <div className="timestamp">
                    <CalendarClock size={14} />
                    <span>{n.dateStr}</span>
                    <button
                      type="button"
                      className="notice-translate"
                      onClick={() => toggleTranslate(n.id)}
                    >
                      <Languages size={13} />
                      <span>{isTranslated ? "Show original" : "Translate"}</span>
                    </button>
                  </div>

                  {/* Top-Right Illustration Icon */}
                  <img
                    className="notice-illustration"
                    src={`/notice-${n.kind}.svg`}
                    alt=""
                    aria-hidden="true"
                  />
                </div>

                {/* Card Footer */}
                <footer>
                  <div className="seen-wrap">
                    <Bell size={16} />
                    <span className="avatars">
                      {n.avatars.map((av, idx) => (
                        <span
                          key={idx}
                          className="avatar-circle"
                          style={{ backgroundColor: av.bg }}
                        >
                          {av.text}
                        </span>
                      ))}
                    </span>
                    <span>{n.seenCount} seen</span>
                  </div>

                  <div className="reactions">
                    <button
                      className={`reaction-btn ${isLiked ? "liked" : ""}`}
                      onClick={() => toggleLike(n.id)}
                      aria-label="Like notice"
                    >
                      <Heart
                        size={17}
                        fill={isLiked ? "currentColor" : "none"}
                      />
                      <span>{n.likeCount}</span>
                    </button>
                    <button className="reaction-btn" aria-label="Comments">
                      <MessageSquare size={17} />
                      <span>{n.commentCount}</span>
                    </button>
                  </div>
                </footer>
              </article>
            );
          })}
        </div>
      </main>

      {/* Floating Bottom Navigation (Exact ClassMate Mobile View) */}
      <nav className="bottom-nav">
        <a href="#timetable" className="nav-item">
          <CalendarDays size={20} />
          <span>Timetable</span>
        </a>
        <a href="#notices" className="nav-item active">
          <div style={{ position: "relative" }}>
            <Bell size={20} />
            <span
              style={{
                position: "absolute",
                top: -1,
                right: -1,
                width: 7,
                height: 7,
                borderRadius: "50%",
                background: "#2563eb",
              }}
            />
          </div>
          <span>Notices</span>
        </a>
        <a href="#courses" className="nav-item">
          <BookOpen size={20} />
          <span>Courses</span>
        </a>
        <a href="#batch" className="nav-item">
          <Users size={20} />
          <span>Batch</span>
        </a>
        <a href="#settings" className="nav-item">
          <Settings size={20} />
          <span>Settings</span>
        </a>
      </nav>
    </div>
  );
}
