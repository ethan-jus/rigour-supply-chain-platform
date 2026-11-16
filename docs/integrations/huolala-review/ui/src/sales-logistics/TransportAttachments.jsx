import React, { useState } from "react";
import { attachmentGroups } from "./attachment-model.mjs";
export default function TransportAttachments({
    record,
    platform,
    results,
    onRetry,
}) {
    const [preview, setPreview] = useState(null);
    const groups = attachmentGroups(record, platform, results);
    return (
        <>
            <section className="sl-section sl-attachments">
                <h3>
                    凭证与运输影像{" "}
                    {platform && (
                        <span className="sl-tag gray">平台资料只读</span>
                    )}
                </h3>
                {groups.map((group) => (
                    <div className="sl-attachment-group" key={group.title}>
                        <h4>
                            {group.title} <small>{group.files.length} 份</small>
                        </h4>
                        {group.title === "运输回单" &&
                            platform?.receiptInfo && (
                                <div className="sl-summary">
                                    {Object.entries(platform.receiptInfo).map(
                                        ([name, value]) => (
                                            <div className="sl-info" key={name}>
                                                <span>{name}</span>
                                                <strong>{value || "-"}</strong>
                                            </div>
                                        ),
                                    )}
                                </div>
                            )}
                        <div className="sl-table-wrap">
                            <table>
                                <thead>
                                    <tr>
                                        <th>
                                            {group.title === "装卸货照片"
                                                ? "发收货点／阶段"
                                                : "所属资料"}
                                        </th>
                                        <th>文件</th>
                                        <th>获取状态</th>
                                        <th>取得时间</th>
                                        <th>操作</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {group.files.map((file) => (
                                        <tr key={file.id}>
                                            <td>{file.group}</td>
                                            <td>{file.name}</td>
                                            <td>
                                                <span
                                                    className={
                                                        "sl-tag " +
                                                        (file.state === "READY"
                                                            ? "green"
                                                            : "orange")
                                                    }
                                                >
                                                    {file.state === "READY"
                                                        ? "已获取"
                                                        : file.state ===
                                                            "FAILED"
                                                          ? "获取失败"
                                                          : "待获取"}
                                                </span>
                                                {file.state !== "READY" &&
                                                    file.reason && (
                                                        <small className="sl-block">
                                                            {file.reason}
                                                        </small>
                                                    )}
                                            </td>
                                            <td>{file.fetchedAt || "-"}</td>
                                            <td>
                                                <div className="sl-row-actions">
                                                    {file.state === "READY" &&
                                                    file.url ? (
                                                        <>
                                                            <button
                                                                className="link"
                                                                onClick={() =>
                                                                    setPreview(
                                                                        file,
                                                                    )
                                                                }
                                                            >
                                                                查看
                                                            </button>
                                                            <a
                                                                href={file.url}
                                                                download={
                                                                    file.name
                                                                }
                                                            >
                                                                下载
                                                            </a>
                                                        </>
                                                    ) : (
                                                        <button
                                                            className="link"
                                                            onClick={() =>
                                                                onRetry(file)
                                                            }
                                                        >
                                                            重试获取
                                                        </button>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                            {!group.files.length && (
                                <div className="sl-empty">{group.empty}</div>
                            )}
                        </div>
                    </div>
                ))}
            </section>
            {preview && (
                <div className="sl-shade sl-file-shade">
                    <section
                        className="sl-file-preview"
                        role="dialog"
                        aria-modal="true"
                        aria-label={"凭证预览 · " + preview.name}
                    >
                        <header>
                            <h3>{preview.name}</h3>
                            <button onClick={() => setPreview(null)}>
                                关闭预览
                            </button>
                        </header>
                        {/\.(png|jpe?g|svg|webp)$/i.test(preview.name) ? (
                            <img src={preview.url} alt={preview.name} />
                        ) : /\.pdf$/i.test(preview.name) ? (
                            <iframe title={preview.name} src={preview.url} />
                        ) : (
                            <p>此文件请下载后查看。</p>
                        )}
                        <footer>
                            <a href={preview.url} download={preview.name}>
                                下载文件
                            </a>
                        </footer>
                    </section>
                </div>
            )}
        </>
    );
}
