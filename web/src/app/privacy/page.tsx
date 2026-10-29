import type { Metadata } from "next";
import PublicPage, { supportEmail } from "@/components/public-page";

export const metadata: Metadata = {
  title: "Privacy Policy | ClassMate",
  description:
    "How ClassMate handles account identity, automated RFID attendance, academic schedules, notifications, and campus community information.",
};

export default function PrivacyPage() {
  return (
    <PublicPage
      policy
      title="Privacy Policy"
      lead="This policy explains the information used by ClassMate across our web platform and Android application, how academic and attendance data is handled, and the controls available to you."
    >
      <section>
        <h2>1. Who Operates ClassMate</h2>
        <p>
          ClassMate is developed and operated by Mohammad Shuaib-Ud-Dowla as an independent smart campus companion and academic automation system for the Mawlana Bhashani Science and Technology University (MBSTU) community.
        </p>
        <p>
          For privacy inquiries, account support, or data questions, contact{" "}
          <a href={`mailto:${supportEmail}`}>{supportEmail}</a>.
        </p>
      </section>

      <section>
        <h2>2. Account Authentication & Identity</h2>
        <p>
          Google Sign-In provides your account email address, full name, and avatar picture. Students sign in with their eligible university institutional email (e.g. <code>@mbstu.ac.bd</code>). Course teachers authenticate using an administrator-approved email.
        </p>
        <p>
          Authentication is verified securely against backend permissions. Google manages your password directly; ClassMate never receives or stores your Google password, nor does it request access to your private Gmail messages or personal Google Drive files.
        </p>
      </section>

      <section>
        <h2>3. Smart Attendance & RFID Tracking</h2>
        <p>
          ClassMate powers automated classroom attendance tracking for university courses. When students tap registered university RFID cards on connected classroom attendance readers, or when attendance is logged digitally by authorized teachers and CRs:
        </p>
        <ul style={{ paddingLeft: "20px", margin: "12px 0", lineHeight: "1.8", color: "var(--muted)" }}>
          <li>The student’s ID, card UID, attendance session, and scan timestamp are recorded.</li>
          <li>Session presence counts, individual attendance percentages, and collegiate eligibility status are calculated for the enrolled course.</li>
          <li>Authorized course teachers and administrators can review attendance matrices and generate official course attendance reports (e.g., Excel spreadsheets) for departmental records.</li>
        </ul>
        <p>
          RFID card IDs are used solely for classroom verification and session check-ins within your enrolled batch and courses.
        </p>
      </section>

      <section>
        <h2>4. Academic Content & Timetable Coordination</h2>
        <p>
          ClassMate maintains academic course schedules, routine slots, room numbers, and real-time class status updates (such as class cancellations or rescheduled periods) posted by course teachers and batch CRs.
        </p>
        <p>
          Notices, academic documents, lecture materials, and question archives shared with your batch remain accessible based on your verified department, session, and course enrollments.
        </p>
      </section>

      <section>
        <h2>5. Campus Community & Emergency Blood Network</h2>
        <p>
          To assist fellow students in emergencies, optional profile information (such as your blood group, district, and contact number) may be registered. 
        </p>
        <p>
          When an urgent blood request is created, compatible campus donors may be notified to facilitate fast volunteer coordination. Contact details are shared solely to connect the patient’s attendant with willing donors; final medical eligibility and screening are determined by qualified hospital healthcare professionals.
        </p>
      </section>

      <section>
        <h2>6. Push Notifications & Local Device Storage</h2>
        <p>
          With your explicit permission, Firebase Cloud Messaging (FCM) delivers urgent class cancellation alerts, new academic notices, and blood donor requests to your registered Android device or web browser.
        </p>
        <p>
          To ensure reliable performance during slow internet connections, the application caches your active timetable, recent batch notices, and hardware roster check-ins locally on your device. You can revoke notification permissions or clear application cache at any time through your browser or Android system settings.
        </p>
      </section>

      <section>
        <h2>7. AI Academic Assistant</h2>
        <p>
          When utilizing the built-in AI academic assistant, queries and authorized course contexts are processed using the Google Gemini API to deliver instant timetable summaries, notice clarifications, or academic guidance. Do not input confidential personal documents or sensitive credentials into conversational AI prompts.
        </p>
      </section>

      <section>
        <h2>8. Service Providers & Infrastructure</h2>
        <p>
          ClassMate relies on trusted cloud infrastructure to deliver high availability:
        </p>
        <ul style={{ paddingLeft: "20px", margin: "12px 0", lineHeight: "1.8", color: "var(--muted)" }}>
          <li><strong>Supabase:</strong> Encrypted PostgreSQL database, row-level security (RLS), and authentication.</li>
          <li><strong>Vercel:</strong> Global edge content delivery and web application hosting.</li>
          <li><strong>Google Cloud &amp; Firebase:</strong> OAuth authentication and push notification delivery.</li>
          <li><strong>Cloudflare:</strong> High-performance serverless workers and encrypted resource distribution.</li>
        </ul>
      </section>

      <section>
        <h2>9. Data Retention & Account Deletion</h2>
        <p>
          Your academic profile and attendance history are retained for the duration of your academic program to provide semester verification and attendance auditing. 
        </p>
        <p>
          You may edit your public profile details at any time in the app settings. To request full deletion of your account and personal records, email{" "}
          <a href={`mailto:${supportEmail}`}>{supportEmail}</a> from your registered university Google account.
        </p>
      </section>
    </PublicPage>
  );
}
