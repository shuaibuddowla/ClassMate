from pathlib import Path

p = Path("web/src/app/r/[id]/page.tsx")
content = p.read_text(encoding="utf-8")

target = """export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const res = await fetchPublicResource(id);
  if (!res) {
    return { title: "Resource Not Found · ClassMate" };
  }
  const course = res.course_title ? `${res.course_title} (${res.course_code || ""})` : "Academic Resource";
  const slugPath = res.slug || res.id;
  return {
    title: `${res.title} · ClassMate`,
    description: `Public shared learning resource for ${course}. Accessible on web or native app without login.`,
    openGraph: {
      title: `${res.title} · ClassMate`,
      description: `Course: ${course}`,
      url: `https://classmatebd.vercel.app/r/${slugPath}`,
      siteName: "ClassMate",
      type: "website",
    },
    twitter: {
      card: "summary",
      title: `${res.title} · ClassMate`,
      description: `Course: ${course}`,
    },
  };
}"""

replacement = """export async function generateMetadata({
  params,
}: {
  params: Promise<{ id: string }>;
}): Promise<Metadata> {
  const { id } = await params;
  const res = await fetchPublicResource(id);
  if (!res) {
    return { title: "Resource Not Found · ClassMate" };
  }
  const course = res.course_title ? `${res.course_title}${res.course_code ? ` (${res.course_code})` : ""}` : "Academic Resource";
  const slugPath = res.slug || res.id;
  const ogImageUrl = `https://classmatebd.vercel.app/r/${slugPath}/opengraph-image`;
  return {
    title: `${res.title} · ClassMate`,
    description: `Course: ${course} · Shared with Batch ${res.batch_number || 22}`,
    openGraph: {
      title: `${res.title} · ClassMate`,
      description: `Course: ${course}`,
      url: `https://classmatebd.vercel.app/r/${slugPath}`,
      siteName: "ClassMate",
      type: "website",
      images: [
        {
          url: ogImageUrl,
          width: 1200,
          height: 630,
          alt: res.title,
        },
      ],
    },
    twitter: {
      card: "summary_large_image",
      title: `${res.title} · ClassMate`,
      description: `Course: ${course}`,
      images: [ogImageUrl],
    },
  };
}"""

if target in content:
    content = content.replace(target, replacement)
elif target.replace('\n', '\r\n') in content:
    content = content.replace(target.replace('\n', '\r\n'), replacement.replace('\n', '\r\n'))
else:
    raise RuntimeError("target not found in page.tsx")

p.write_text(content, encoding="utf-8")
print("Updated page.tsx generateMetadata successfully")
