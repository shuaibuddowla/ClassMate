"use client";
import { academicSession } from "@/lib/academic-session";
import { useQuery } from "@tanstack/react-query";
import { Github, Globe, ArrowUpRight, Code2 } from "lucide-react";
import { rpc, type Row } from "@/lib/api";
import { Avatar, ErrorBox, Modal, Skeleton } from "./ui";

export function AboutDeveloper({
  user,
  close,
}: {
  user: string;
  close: () => void;
}) {
  const profile = useQuery({
    queryKey: [user, "developer-profile"],
    queryFn: () => rpc<Row | null>("developer_profile"),
    staleTime: 300_000,
  });
  return (
    <Modal title="About developer" close={close}>
      <div className="developer-panel">
        <div className="developer-hero">
          <span className="developer-signature"><Code2 size={16} /> DESIGNED & BUILT BY</span>
          {profile.isPending ? (
            <Skeleton />
          ) : profile.data ? (
            <>
              <Avatar
                name={profile.data.full_name}
                url={profile.data.avatar_url}
              />
              <h3>{profile.data.full_name}</h3>
              <p>{profile.data.department || "Computer Science and Engineering"}</p>
              <div className="developer-identity">
                {profile.data.batch_number && (
                  <span>Batch {profile.data.batch_number}</span>
                )}
                {profile.data.academic_session && (
                  <span>
                    Session {academicSession(profile.data.academic_session)}
                  </span>
                )}
              </div>
            </>
          ) : (
            !profile.error && <p>Developer profile is unavailable.</p>
          )}
        </div>
        <ErrorBox error={profile.error} retry={() => profile.refetch()} />
        <div className="developer-links">
          {[
            ["GitHub", "github.com/shuaibuddowla", Github],
            ["Portfolio", "shuaibuddowla.github.io", Globe],
          ].map(([label, path, Icon]) => {
            const Mark = Icon as typeof Globe;
            return (
              <a
                key={String(label)}
                href={`https://${path}`}
                target="_blank"
                rel="noopener noreferrer"
              >
                <Mark size={21} />
                <span>
                  <strong>{String(label)}</strong>
                  <small>{String(path)}</small>
                </span>
                <ArrowUpRight size={17} />
              </a>
            );
          })}
        </div>
        <small className="developer-caption">
          A student-built space for a more connected campus.
        </small>
      </div>
    </Modal>
  );
}
