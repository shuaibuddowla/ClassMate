from pathlib import Path

p = Path("app/src/main/res/layout/classmate_profile.xml")
content = p.read_text(encoding="utf-8")

target = """                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnClearCache"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="match_parent"
                        android:layout_height="40dp"
                        android:layout_marginTop="10dp"
                        android:paddingHorizontal="12dp"
                        android:text="Clear Cache"
                        android:textSize="12sp"
                        app:cornerRadius="10dp"
                        app:strokeColor="@color/cm_border" />
                </LinearLayout>"""

replacement = """                    <com.google.android.material.button.MaterialButton
                        android:id="@+id/btnClearCache"
                        style="?attr/materialButtonOutlinedStyle"
                        android:layout_width="match_parent"
                        android:layout_height="40dp"
                        android:layout_marginTop="10dp"
                        android:paddingHorizontal="12dp"
                        android:text="Clear Cache"
                        android:textSize="12sp"
                        app:cornerRadius="10dp"
                        app:strokeColor="@color/cm_border" />
                </LinearLayout>

                <View
                    android:id="@+id/dividerR2Storage"
                    android:layout_width="match_parent"
                    android:layout_height="1dp"
                    android:background="@color/cm_border"
                    android:layout_marginHorizontal="16dp"
                    android:visibility="gone"
                    tools:visibility="visible" />

                <!-- Cloudflare R2 Storage (Admin & CR only) -->
                <LinearLayout
                    android:id="@+id/layoutR2Storage"
                    android:layout_width="match_parent"
                    android:layout_height="70dp"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:paddingHorizontal="16dp"
                    android:background="?attr/selectableItemBackground"
                    android:clickable="true"
                    android:focusable="true"
                    android:visibility="gone"
                    tools:visibility="visible">
                    <ImageView
                        android:layout_width="38dp"
                        android:layout_height="38dp"
                        android:padding="8.5dp"
                        android:background="@drawable/bg_settings_icon"
                        android:src="@drawable/ic_cloud"
                        app:tint="@color/cm_primary"
                        android:importantForAccessibility="no"/>
                    <LinearLayout
                        android:layout_width="0dp"
                        android:layout_height="wrap_content"
                        android:layout_weight="1"
                        android:orientation="vertical"
                        android:layout_marginStart="14dp">
                        <LinearLayout
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:orientation="horizontal"
                            android:gravity="center_vertical">
                            <TextView
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:text="Cloudflare R2 storage"
                                android:textColor="@color/cm_text_primary"
                                android:textSize="15sp"
                                android:textStyle="bold" />
                            <TextView
                                android:id="@+id/tvR2StoragePill"
                                android:layout_width="wrap_content"
                                android:layout_height="wrap_content"
                                android:layout_marginStart="8dp"
                                android:paddingHorizontal="6dp"
                                android:paddingVertical="1.5dp"
                                android:background="@drawable/bg_r2_status_pill"
                                android:text="10 GB Limit"
                                android:textColor="@color/cm_primary"
                                android:textSize="9.5sp"
                                android:textStyle="bold"/>
                        </LinearLayout>
                        <TextView
                            android:id="@+id/tvR2StorageSubtitle"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="2dp"
                            android:text="Bucket records and 9.9 GB quota limit"
                            android:textColor="@color/cm_text_secondary"
                            android:textSize="11.5sp" />
                    </LinearLayout>
                    <ImageButton
                        android:layout_width="20dp"
                        android:layout_height="20dp"
                        android:src="@drawable/ic_chevron_right"
                        android:background="@android:color/transparent"
                        android:clickable="false"
                        android:focusable="false"
                        app:tint="@color/cm_text_secondary"
                        android:importantForAccessibility="no"/>
                </LinearLayout>"""

if target in content:
    content = content.replace(target, replacement)
elif target.replace('\n', '\r\n') in content:
    content = content.replace(target.replace('\n', '\r\n'), replacement.replace('\n', '\r\n'))
else:
    raise RuntimeError("target not found in classmate_profile.xml")

p.write_text(content, encoding="utf-8")
print("Updated classmate_profile.xml successfully")
